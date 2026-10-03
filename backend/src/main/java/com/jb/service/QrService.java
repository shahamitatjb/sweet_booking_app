package com.jb.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class QrService {
    @Value("${jb.qr-hmac-secret}")
    private String secret;

    @Value("${jb.qr-key-id}")
    private String keyId;

    public static String sign(String bookingId, int amountPaise, long confirmedAtEpoch, String keyId) {
        String payload = bookingId + "|" + amountPaise + "|" + confirmedAtEpoch + "|" + keyId;
        return hmacBase64Url(payload);
    }

    public String signCurrent(String bookingId, int amountPaise, long confirmedAtEpoch) {
        return sign(bookingId, amountPaise, confirmedAtEpoch, keyId);
    }

    public boolean verify(String bookingId, int amountPaise, long confirmedAtEpoch, String providedSignature) {
        if (providedSignature == null || providedSignature.isBlank()) return false;
        String expected = signCurrent(bookingId, amountPaise, confirmedAtEpoch);
        return constantTimeEquals(expected, providedSignature);
    }

    public String verificationPath(String bookingId, String signature) {
        return "/v/" + bookingId + "." + signature;
    }

    public String verificationUrl(String baseUrl, String bookingId, String signature) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + verificationPath(bookingId, signature);
    }

    private static String hmacBase64Url(String payload) {
        try {
            // secret resolved from instance in instance methods; static path used by finalize via env fallback
            String secretVal = System.getenv("QR_HMAC_SECRET");
            if (secretVal == null || secretVal.isBlank()) {
                secretVal = "dev-only-change-me-32bytes-min!!";
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretVal.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] first16 = new byte[16];
            System.arraycopy(raw, 0, first16, 0, 16);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(first16);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
