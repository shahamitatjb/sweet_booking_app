package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns a confirmed Razorpay payment into a booking. Two entry points, both idempotent
 * through {@link BookingFinalizeService}: the browser's checkout callback and the webhook.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final OrderRepository orderRepository;
    private final BookingFinalizeService finalizeService;
    private final RazorpayService razorpayService;

    public enum WebhookOutcome { REJECTED, IGNORED, FINALIZED }

    public record WebhookResult(WebhookOutcome outcome, String bookingId, String error) {
        public static WebhookResult rejected(String error) { return new WebhookResult(WebhookOutcome.REJECTED, null, error); }
        public static WebhookResult ignored() { return new WebhookResult(WebhookOutcome.IGNORED, null, null); }
        public static WebhookResult finalized(String bookingId) { return new WebhookResult(WebhookOutcome.FINALIZED, bookingId, null); }
    }

    /** {@code bankReferencePending}: the bank reference could not be fetched yet; the webhook fills it in. */
    public record Confirmed(Booking booking, boolean bankReferencePending) {}

    /** Browser callback: the three fields Razorpay Checkout hands back after a successful payment. */
    public Confirmed verifyAndFinalize(UUID orderId, String gatewayOrderId, String paymentId, String signature) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (order.getGatewayOrderId() == null || !order.getGatewayOrderId().equals(gatewayOrderId)) {
            log.warn("[PAY] verify rejected: gateway order mismatch for orderId={} (ours={} theirs={})",
                    orderId, order.getGatewayOrderId(), gatewayOrderId);
            throw new IllegalArgumentException("Payment does not belong to this order");
        }
        if (!razorpayService.verifyPaymentSignature(gatewayOrderId, paymentId, signature)) {
            log.warn("[PAY] verify rejected: bad signature for orderId={} paymentId={}", orderId, paymentId);
            throw new IllegalArgumentException("Invalid payment signature");
        }
        log.info("[PAY] verify OK for orderId={} paymentId={}; finalising", orderId, paymentId);
        Booking booking = finalizeService.finalizeOnline(orderId, paymentId, order.getTotalAmount());
        boolean hasRef = false;
        try {
            hasRef = finalizeService.recordBankReference(orderId,
                    razorpayService.fetchBankReference(paymentId).orElse(null));
        } catch (RuntimeException e) {
            // The booking is already issued; a missing reference must not fail the confirmation.
            log.warn("[PAY] bank reference not stored for orderId={}: {}", orderId, e.toString());
        }
        return new Confirmed(booking, !hasRef);
    }

    /** Webhook: signature first, then only payment.captured for an order we know. */
    public WebhookResult handleWebhook(String body, String signatureHeader) {
        if (!razorpayService.verifyWebhookSignature(body, signatureHeader)) {
            return WebhookResult.rejected("invalid signature");
        }
        Optional<RazorpayWebhookPayload.CapturedPayment> captured = RazorpayWebhookPayload.parseCaptured(body);
        if (captured.isEmpty()) {
            log.info("[PAY] webhook acknowledged, not a payment.captured event");
            return WebhookResult.ignored();
        }
        var p = captured.get();
        Optional<Order> order = orderRepository.findByGatewayOrderId(p.gatewayOrderId());
        if (order.isEmpty()) {
            log.warn("[PAY] webhook payment.captured for unknown gatewayOrderId={} paymentId={}",
                    p.gatewayOrderId(), p.paymentId());
            return WebhookResult.ignored();
        }
        try {
            Booking booking = finalizeService.finalizeOnline(order.get().getId(), p.paymentId(), p.amountPaise());
            try {
                finalizeService.recordBankReference(order.get().getId(), p.bankReference());
            } catch (RuntimeException e) {
                log.warn("[PAY] webhook bank reference not stored for orderId={}: {}", order.get().getId(), e.toString());
            }
            log.info("[PAY] webhook finalised orderId={} -> {}", order.get().getId(), booking.getBookingId());
            return WebhookResult.finalized(booking.getBookingId());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Terminal for this event (amount mismatch, voided order): acknowledge with 200 so Razorpay
            // does not retry for 24h or disable the webhook. Only a bad signature is answered with 400.
            log.error("[PAY] webhook payment.captured NOT finalised for gatewayOrderId={} paymentId={} amountPaise={}: {}",
                    p.gatewayOrderId(), p.paymentId(), p.amountPaise(), e.getMessage());
            return WebhookResult.ignored();
        }
    }
}
