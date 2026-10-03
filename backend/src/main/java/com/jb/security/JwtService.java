package com.jb.security;

import com.jb.domain.Staff;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {
    @Value("${jb.jwt-secret}")
    private String secret;

    @Value("${jb.jwt-ttl-minutes}")
    private long ttlMinutes;

    public String issue(Staff staff) {
        Instant exp = Instant.now().plus(Duration.ofMinutes(ttlMinutes));
        return Jwts.builder()
                .claims(Map.of(
                        "sid", staff.getId(),
                        "email", staff.getEmail(),
                        "role", staff.getRole().name(),
                        "name", staff.getName() == null ? "" : staff.getName()
                ))
                .subject(staff.getEmail())
                .issuedAt(new Date())
                .expiration(Date.from(exp))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try {
            Claims c = parse(token);
            return c.getExpiration() != null && c.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey key() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
