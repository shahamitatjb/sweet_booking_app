package com.jb.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how many online orders one mobile number and one client IP can start per hour, so a
 * script cannot flood the database and Razorpay with unpaid orders. In memory: limits reset
 * when the API restarts, which is acceptable for a single instance. The IP limit is kept
 * generous because many customers share an IP behind mobile carriers and venue Wi-Fi.
 */
@Slf4j
@Service
public class OrderRateLimiter {
    static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED_KEYS = 50_000;

    private final int perMobile;
    private final int perIp;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    @Autowired
    public OrderRateLimiter(@Value("${jb.order-limit-per-mobile-per-hour:5}") int perMobile,
                            @Value("${jb.order-limit-per-ip-per-hour:30}") int perIp) {
        this(perMobile, perIp, Clock.systemUTC());
    }

    OrderRateLimiter(int perMobile, int perIp, Clock clock) {
        this.perMobile = perMobile;
        this.perIp = perIp;
        this.clock = clock;
    }

    /** Records one order attempt; false (and nothing recorded) when either limit is already reached. */
    public synchronized boolean tryAcquire(String mobile, String clientIp) {
        Instant now = clock.instant();
        if (hits.size() > MAX_TRACKED_KEYS) prune(now);
        Deque<Instant> byMobile = recent("m:" + mobile, now);
        Deque<Instant> byIp = recent("ip:" + clientIp, now);
        if (byMobile.size() >= perMobile || byIp.size() >= perIp) {
            log.warn("[BOOKING] order rate limit hit: mobileAttempts={} ipAttempts={} ip={}",
                    byMobile.size(), byIp.size(), clientIp);
            return false;
        }
        byMobile.addLast(now);
        byIp.addLast(now);
        return true;
    }

    private Deque<Instant> recent(String key, Instant now) {
        Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(WINDOW);
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) q.pollFirst();
        return q;
    }

    private void prune(Instant now) {
        hits.keySet().forEach(k -> {
            if (recent(k, now).isEmpty()) hits.remove(k);
        });
    }
}
