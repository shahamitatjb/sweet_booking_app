package com.jb.web;

import com.jb.service.BookingFinalizeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Local-only test helper: finalise an awaiting_payment order with a fake payment id.
 * Not registered under any other profile, so on Render the route does not exist (404).
 */
@Slf4j
@Profile("local")
@RestController
@RequestMapping("/api/webhooks/razorpay")
@RequiredArgsConstructor
public class DemoFinalizeController {
    private final BookingFinalizeService finalizeService;

    @PostMapping("/finalize-demo")
    public ResponseEntity<?> finalizeDemo(@RequestBody Map<String, String> body) {
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
