package com.jb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {
    public enum Channel { sms, email, dev }

    @Value("${jb.otp-provider}")
    private String provider;

    @Value("${jb.otp-ttl-minutes}")
    private long ttlMinutes;

    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    public record OtpRequest(String destination, String channel) {}
    public record OtpIssue(String channel, String destination, String devCode) {}

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static final class Attempt {
        final AtomicInteger count = new AtomicInteger();
        volatile Instant resetAt;
    }

    public OtpIssue issue(String destination, Channel channel) {
        String dest = normalize(destination);
        if (!isValidDestination(channel, dest)) {
            throw new IllegalArgumentException("Invalid OTP destination");
        }
        rateLimit(dest, channel);
        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expires = Instant.now().plus(Duration.ofMinutes(ttlMinutes));
        String providerName = resolveProvider(channel);

        if ("dev".equals(providerName) || "console".equals(providerName)) {
            log.info("OTP dev code for {} via {}: {}", dest, channel, code);
            return new OtpIssue(channel.name(), dest, code);
        }
        if (channel == Channel.email || "email".equals(providerName)) {
            emailService.sendSimple(dest, "Your booking OTP", "Your OTP is " + code + ". Valid for " + ttlMinutes + " minutes.");
            return new OtpIssue("email", dest, null);
        }
        // SMS provider not wired until DLT — production path should set OTP_PROVIDER=sms only when ready
        throw new IllegalStateException("SMS OTP provider not configured. Use email OTP or enable SMS after DLT.");
    }

    public boolean verify(String destination, Channel channel, String code, String provided) {
        if (provided == null || code == null) return false;
        return MessageDigest.isEqual(code.getBytes(), provided.trim().getBytes());
    }

    public void clearAttempts(String destination) {
        attempts.remove(normalize(destination));
    }

    private void rateLimit(String dest, Channel channel) {
        String key = channel.name() + ":" + dest;
        Attempt a = attempts.computeIfAbsent(key, k -> new Attempt());
        if (a.resetAt != null && Instant.now().isAfter(a.resetAt)) {
            a.count.set(0);
            a.resetAt = null;
        }
        if (a.count.incrementAndGet() > 5) {
            throw new IllegalArgumentException("Too many OTP requests. Try later.");
        }
    }

    private String resolveProvider(Channel channel) {
        String p = provider == null ? "email" : provider.trim().toLowerCase();
        if ("dev".equals(p)) return "dev";
        if ("sms".equals(p) && channel == Channel.email) return "email";
        return p;
    }

    private boolean isValidDestination(Channel channel, String dest) {
        if (channel == Channel.email) {
            return dest.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
        }
        return dest.matches("[6-9]\\d{9}");
    }

    public String normalize(String destination) {
        if (destination == null) return "";
        String d = destination.trim();
        if (d.startsWith("+91") && d.length() == 13) return d.substring(3);
        if (d.startsWith("91") && d.length() == 12) return d.substring(2);
        return d;
    }
}
