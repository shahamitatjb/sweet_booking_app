package com.jb.web;

import com.jb.service.BookingFinalizeService;
import com.jb.service.RazorpayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {
    private final RazorpayService razorpayService;
    private final BookingFinalizeService finalizeService;

    @PostMapping("/razorpay")
    public ResponseEntity<?> razorpay(@RequestBody String body,
                                      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        log.info("[PAY] Razorpay webhook received: bodyBytes={} signaturePresent={}",
                body == null ? 0 : body.length(), signature != null);
        if (!razorpayService.verifyWebhookSignature(body, signature)) {
            log.warn("[PAY] Razorpay webhook REJECTED (signature) — 400 returned");
            return ResponseEntity.status(400).body(Map.of("error", "invalid signature"));
        }
        // Parse event in production with org.json; finalize on payment.captured
        // Idempotent: finalizeOnline returns existing booking if already paid
        log.info("[PAY] Razorpay webhook accepted and acknowledged (event parsing not yet implemented)");
        return ResponseEntity.ok(Map.of("received", true));
    }

    @PostMapping("/razorpay/finalize-demo")
    public ResponseEntity<?> finalizeDemo(@RequestBody Map<String, String> body) {
        // Test helper for staging: finalize an awaiting_payment order with a fake payment id
        try {
            UUID orderId = UUID.fromString(body.get("orderId"));
            String paymentId = body.getOrDefault("gatewayPaymentId", "pay_demo_" + orderId);
            int amount = Integer.parseInt(body.get("amountPaise"));
            log.info("[BOOKING] finalize-demo: orderId={} amountPaise={} paymentId={}", orderId, amount, paymentId);
            var booking = finalizeService.finalizeOnline(orderId, paymentId, amount);
            log.info("[BOOKING] finalize-demo OK: orderId={} -> {}", orderId, booking.getBookingId());
            return ResponseEntity.ok(Map.of("bookingId", booking.getBookingId()));
        } catch (Exception e) {
            log.error("[BOOKING] finalize-demo FAILED for orderId={} : {}", body.get("orderId"), e.toString(), e);
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
