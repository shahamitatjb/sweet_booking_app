package com.jb.web;

import com.jb.domain.Booking;
import com.jb.service.PaymentService;
import com.jb.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Razorpay Checkout success callback: verify the signature server-side, then issue the booking. */
@Slf4j
@RestController
@RequestMapping("/api/public/payments")
@RequiredArgsConstructor
public class PublicPaymentController {
    /** Clients get one fixed message; the real reason stays in the server log. */
    public static final String GENERIC_FAILURE = "Payment could not be confirmed";
    public static final String PENDING_MESSAGE = "Payment received. We are confirming it with the bank.";

    private final PaymentService paymentService;
    private final ReceiptService receiptService;

    public record VerifyRequest(String orderId, String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {}

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest req) {
        UUID orderId;
        try {
            orderId = UUID.fromString(require(req.orderId(), "orderId"));
            require(req.razorpayOrderId(), "razorpayOrderId");
            require(req.razorpayPaymentId(), "razorpayPaymentId");
            require(req.razorpaySignature(), "razorpaySignature");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid payment confirmation"));
        }
        try {
            PaymentService.Confirmed confirmed = paymentService.verifyAndFinalize(
                    orderId, req.razorpayOrderId(), req.razorpayPaymentId(), req.razorpaySignature());
            if (confirmed.confirmationPending()) {
                // Razorpay could not be asked yet. No booking: the browser retries this call, and the
                // webhook or the reconciliation job confirms it once Razorpay reports the payment captured.
                return ResponseEntity.accepted().body(Map.of("status", "pending", "message", PENDING_MESSAGE));
            }
            Booking booking = confirmed.booking();
            return ResponseEntity.ok(Map.of(
                    "bookingId", booking.getBookingId(),
                    "receiptUrl", receiptService.receiptPath(booking),
                    "transactionRefPending", confirmed.bankReferencePending()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[PAY] verify failed for orderId={}: {}", orderId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", GENERIC_FAILURE));
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
        return value;
    }
}
