package com.jb.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Issues one-time codes and verifies them against a server-side store.
 * Codes are kept hashed, expire after {@code jb.otp-ttl-minutes}, are consumed on
 * first successful verification and dropped after too many wrong attempts.
 * The store is in-memory: the API runs as a single instance.
 */
@Service
@Slf4j
public class OtpService {
    public enum Channel { sms, email, dev }

    private static final int MAX_REQUESTS_PER_WINDOW = 5;
    private static final Duration REQUEST_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_VERIFY_FAILURES = 5;
    private static final String MOBILE_PATTERN = "[6-9]\\d{9}";
    private static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private final EmailService emailService;
    private final String provider;
    private final long ttlMinutes;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, IssuedCode> issued = new ConcurrentHashMap<>();

    public record OtpIssue(String channel, String destination, String devCode) {}

    private static final class Attempt {
        final AtomicInteger count = new AtomicInteger();
        volatile Instant resetAt;
    }

    private static final class IssuedCode {
        final byte[] hash;
        final Instant expiresAt;
        final AtomicInteger failures = new AtomicInteger();

        IssuedCode(byte[] hash, Instant expiresAt) {
            this.hash = hash;
            this.expiresAt = expiresAt;
        }
    }

    @Autowired
    public OtpService(EmailService emailService,
                      @Value("${jb.otp-provider}") String provider,
                      @Value("${jb.otp-ttl-minutes}") long ttlMinutes) {
        this(emailService, provider, ttlMinutes, Clock.systemUTC());
    }

    OtpService(EmailService emailService, String provider, long ttlMinutes, Clock clock) {
        this.emailService = emailService;
        this.provider = provider;
        this.ttlMinutes = ttlMinutes;
        this.clock = clock;
    }

    public OtpIssue issue(String destination, Channel channel) {
        String dest = normalize(destination);
        if (!isValidDestination(channel, dest)) {
            log.warn("[OTP] rejected: invalid {} destination '{}'", channel, mask(dest));
            throw new IllegalArgumentException("Invalid OTP destination");
        }
        rateLimit(dest, channel);
        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expires = clock.instant().plus(Duration.ofMinutes(ttlMinutes));
        issued.put(key(channel, dest), new IssuedCode(hash(dest, code), expires));

        String providerName = resolveProvider(channel);
        if ("dev".equals(providerName) || "console".equals(providerName)) {
            log.info("[OTP] dev code for {} via {}: {}", mask(dest), channel, code);
            return new OtpIssue(channel.name(), dest, code);
        }
        if (channel == Channel.email || "email".equals(providerName)) {
            emailService.sendSimple(dest, "Your booking OTP",
                    "Your OTP is " + code + ". Valid for " + ttlMinutes + " minutes.");
            log.info("[OTP] emailed OTP to {} (expires {})", mask(dest), expires);
            return new OtpIssue("email", dest, null);
        }
        issued.remove(key(channel, dest));
        log.error("[OTP] SMS provider not configured — request for {} failed", mask(dest));
        throw new IllegalStateException("SMS OTP provider not configured. Use email OTP or enable SMS after DLT.");
    }

    /** Checks the typed code against the stored one; consumes it on success. */
    public boolean verify(String destination, Channel channel, String provided) {
        String dest = normalize(destination);
        String k = key(channel, dest);
        IssuedCode stored = issued.get(k);
        if (stored == null || provided == null || provided.isBlank()) {
            log.warn("[OTP] verify failed: no code issued or none provided (destination={} channel={})", mask(dest), channel);
            return false;
        }
        if (clock.instant().isAfter(stored.expiresAt)) {
            issued.remove(k);
            log.warn("[OTP] verify failed: code expired for {} via {}", mask(dest), channel);
            return false;
        }
        boolean ok = MessageDigest.isEqual(stored.hash, hash(dest, provided.trim()));
        if (ok) {
            issued.remove(k);
            attempts.remove(k);
            log.info("[OTP] verify OK for {} via {}", mask(dest), channel);
            return true;
        }
        int failures = stored.failures.incrementAndGet();
        if (failures >= MAX_VERIFY_FAILURES) {
            issued.remove(k);
            log.warn("[OTP] verify FAILED {} times for {} via {} — code invalidated", failures, mask(dest), channel);
        } else {
            log.warn("[OTP] verify FAILED (mismatch {}/{}) for {} via {}", failures, MAX_VERIFY_FAILURES, mask(dest), channel);
        }
        return false;
    }

    public void clearAttempts(String destination) {
        String dest = normalize(destination);
        for (Channel c : Channel.values()) attempts.remove(key(c, dest));
    }

    private void rateLimit(String dest, Channel channel) {
        String k = key(channel, dest);
        Attempt a = attempts.computeIfAbsent(k, x -> new Attempt());
        Instant now = clock.instant();
        if (a.resetAt == null || now.isAfter(a.resetAt)) {
            a.count.set(0);
            a.resetAt = now.plus(REQUEST_WINDOW);
        }
        int n = a.count.incrementAndGet();
        if (n > MAX_REQUESTS_PER_WINDOW) {
            log.warn("[OTP] rate limit hit for {} ({} requests in window)", mask(dest), n);
            throw new IllegalArgumentException("Too many OTP requests. Try later.");
        }
        log.info("[OTP] rate counter for {}: {}/{}", mask(dest), n, MAX_REQUESTS_PER_WINDOW);
    }

    private String resolveProvider(Channel channel) {
        String p = provider == null ? "email" : provider.trim().toLowerCase();
        if ("dev".equals(p)) return "dev";
        if ("sms".equals(p) && channel == Channel.email) return "email";
        return p;
    }

    private static boolean isValidDestination(Channel channel, String dest) {
        if (channel == Channel.email) return dest.matches(EMAIL_PATTERN);
        return dest.matches(MOBILE_PATTERN);
    }

    private static String key(Channel channel, String dest) {
        return channel.name() + ":" + dest.toLowerCase();
    }

    private static byte[] hash(String dest, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest((dest.toLowerCase() + ":" + code).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String mask(String dest) {
        if (dest == null || dest.length() < 4) return "***";
        return "***" + dest.substring(dest.length() - 4);
    }

    public String normalize(String destination) {
        if (destination == null) return "";
        String d = destination.trim();
        if (d.startsWith("+91") && d.length() == 13) return d.substring(3);
        if (d.startsWith("91") && d.length() == 12) return d.substring(2);
        return d;
    }
}
