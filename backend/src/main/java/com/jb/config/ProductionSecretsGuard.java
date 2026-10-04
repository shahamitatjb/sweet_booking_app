package com.jb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start outside development unless the secrets that protect bookings are real:
 * the staff login (JWT) and receipt QR secrets must be set, at least 32 bytes and not the
 * built-in development values, and Razorpay keys need their webhook secret (refunds and
 * disputes only arrive by webhook). A failed deploy keeps the previous version serving.
 */
@Slf4j
@Component
public class ProductionSecretsGuard implements InitializingBean {
    static final Set<String> DEV_PROFILES = Set.of("local", "e2e", "it", "test");
    static final Set<String> DEV_SECRETS = Set.of(
            "dev-only-change-me-32bytes-min!!",
            "dev-jwt-secret-change-me-32bytes-minimum!",
            "change-me-32-bytes-minimum-secret!!");

    private final Environment env;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        if (Arrays.stream(env.getActiveProfiles()).anyMatch(DEV_PROFILES::contains)) {
            log.warn("[CONFIG] development profile {} active — production secret checks skipped",
                    Arrays.toString(env.getActiveProfiles()));
            return;
        }
        List<String> problems = problems(
                env.getProperty("jb.jwt-secret"),
                env.getProperty("jb.qr-hmac-secret"),
                env.getProperty("jb.razorpay-key-id"),
                env.getProperty("jb.razorpay-key-secret"),
                env.getProperty("jb.razorpay-webhook-secret"));
        if (!problems.isEmpty()) {
            problems.forEach(p -> log.error("[CONFIG] {}", p));
            throw new IllegalStateException("Refusing to start without production secrets: " + String.join("; ", problems));
        }
        log.info("[CONFIG] production secrets present");
    }

    static List<String> problems(String jwtSecret, String qrSecret, String rzpKeyId, String rzpKeySecret,
                                 String rzpWebhookSecret) {
        List<String> out = new ArrayList<>();
        checkSecret(out, "JWT_SECRET", jwtSecret);
        checkSecret(out, "QR_HMAC_SECRET", qrSecret);
        if (!blank(rzpKeyId)) {
            if (blank(rzpKeySecret)) out.add("RAZORPAY_KEY_SECRET is not set");
            if (blank(rzpWebhookSecret)) {
                out.add("RAZORPAY_WEBHOOK_SECRET is not set (refunds and disputes would not cancel bookings)");
            }
        }
        return out;
    }

    private static void checkSecret(List<String> out, String name, String value) {
        if (blank(value)) {
            out.add(name + " is not set");
        } else if (DEV_SECRETS.contains(value)) {
            out.add(name + " is the built-in development value");
        } else if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
            out.add(name + " is shorter than 32 bytes");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
