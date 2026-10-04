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

    /** Signature printed in the QR: HMAC of the booking's identity with the configured QR secret. */
    public String sign(String bookingId, int amountPaise, long confirmedAtEpoch, String keyId) {
        String payload = bookingId + "|" + amountPaise + "|" + confirmedAtEpoch + "|" + keyId;
        return hmacBase64Url(secret, payload);
    }

    public String signCurrent(String bookingId, int amountPaise, long confirmedAtEpoch) {
        return sign(bookingId, amountPaise, confirmedAtEpoch, keyId);
    }

    /** {@code bookingKeyId} is the key id stored on the booking when it was signed. */
    public boolean verify(String bookingId, int amountPaise, long confirmedAtEpoch, String bookingKeyId,
                          String providedSignature) {
        if (providedSignature == null || providedSignature.isBlank()) return false;
        String expected = sign(bookingId, amountPaise, confirmedAtEpoch,
                bookingKeyId == null || bookingKeyId.isBlank() ? keyId : bookingKeyId);
        return constantTimeEquals(expected, providedSignature);
    }

    public String verificationPath(String bookingId, String signature) {
        return "/v/" + bookingId + "." + signature;
    }

    public String verificationUrl(String baseUrl, String bookingId, String signature) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + verificationPath(bookingId, signature);
    }

    private static String hmacBase64Url(String secretVal, String payload) {
        try {
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
