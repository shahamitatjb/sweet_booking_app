package com.jb.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRateLimiterTest {
    private Instant now = Instant.parse("2026-10-20T10:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    @Test
    void limitsPerMobileAndRecoversAfterTheWindow() {
        OrderRateLimiter limiter = new OrderRateLimiter(2, 100, clock);
        assertThat(limiter.tryAcquire("9876543210", "1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("9876543210", "2.2.2.2")).isTrue();
        assertThat(limiter.tryAcquire("9876543210", "3.3.3.3")).isFalse();
        assertThat(limiter.tryAcquire("9123456780", "1.1.1.1")).isTrue();

        now = now.plus(Duration.ofMinutes(61));
        assertThat(limiter.tryAcquire("9876543210", "1.1.1.1")).isTrue();
    }

    @Test
    void limitsPerIpAcrossMobiles() {
        OrderRateLimiter limiter = new OrderRateLimiter(100, 2, clock);
        assertThat(limiter.tryAcquire("9000000001", "5.5.5.5")).isTrue();
        assertThat(limiter.tryAcquire("9000000002", "5.5.5.5")).isTrue();
        assertThat(limiter.tryAcquire("9000000003", "5.5.5.5")).isFalse();
    }

    @Test
    void aRefusedAttemptDoesNotCountAgainstTheOtherKey() {
        OrderRateLimiter limiter = new OrderRateLimiter(1, 2, clock);
        assertThat(limiter.tryAcquire("9000000001", "5.5.5.5")).isTrue();
        assertThat(limiter.tryAcquire("9000000001", "5.5.5.5")).isFalse(); // mobile full; IP not charged
        assertThat(limiter.tryAcquire("9000000002", "5.5.5.5")).isTrue();
    }
}
