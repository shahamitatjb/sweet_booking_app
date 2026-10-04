package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.domain.Payment;
import com.jb.repository.BookingRepository;
import com.jb.repository.OrderRepository;
import com.jb.repository.PaymentRepository;
import com.jb.service.RazorpayService.GatewayPayment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns a Razorpay payment into a booking, and cancels it when the money goes back.
 *
 * <p>The one rule: an online booking is issued only for a payment that Razorpay itself reports
 * as <b>captured</b>, for <b>this</b> order, for the <b>full amount</b> in INR. The checkout
 * signature alone is not enough (it also covers payments that are only authorized), so the
 * callback asks Razorpay for the payment, captures it if it is only authorized, and confirms
 * only once it is captured. When Razorpay cannot be reached the booking stays unconfirmed and
 * the webhook or {@link com.jb.jobs.StuckPaymentJob} confirms it later.
 *
 * <p>Refunds (any amount) and disputes cancel the order, before or after the booking was issued.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final OrderRepository orderRepository;
    private final BookingFinalizeService finalizeService;
    private final RazorpayService razorpayService;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingAdminService bookingAdminService;

    static final String CURRENCY = "INR";

    public enum WebhookOutcome { REJECTED, IGNORED, FINALIZED, CANCELLED }

    public record WebhookResult(WebhookOutcome outcome, String bookingId, String error) {
        public static WebhookResult rejected(String error) { return new WebhookResult(WebhookOutcome.REJECTED, null, error); }
        public static WebhookResult ignored() { return new WebhookResult(WebhookOutcome.IGNORED, null, null); }
        public static WebhookResult finalized(String bookingId) { return new WebhookResult(WebhookOutcome.FINALIZED, bookingId, null); }
        public static WebhookResult cancelled(String bookingId) { return new WebhookResult(WebhookOutcome.CANCELLED, bookingId, null); }
    }

    /**
     * {@code booking} is null while the payment is still being confirmed with Razorpay.
     * {@code bankReferencePending}: the bank reference is not known yet; the webhook fills it in.
     */
    public record Confirmed(Booking booking, boolean bankReferencePending) {
        static Confirmed pending() { return new Confirmed(null, true); }

        public boolean confirmationPending() { return booking == null; }
    }

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
        // A retry after the webhook or the reconciliation job already confirmed it.
        Optional<Booking> existing = bookingRepository.findByOrderId(orderId);
        if (existing.isPresent() && order.getStatus() == Order.Status.paid) {
            return new Confirmed(existing.get(), ReceiptService.transactionRef(order) == null);
        }
        log.info("[PAY] verify signature OK for orderId={} paymentId={}; checking payment with Razorpay", orderId, paymentId);
        try {
            GatewayPayment payment = ensureCaptured(order, razorpayService.fetchPayment(paymentId));
            return confirmCaptured(order, payment);
        } catch (GatewayUnavailableException e) {
            log.warn("[PAY] verify PENDING for orderId={} paymentId={}: Razorpay unreachable, booking not confirmed yet",
                    orderId, paymentId);
            return Confirmed.pending();
        }
    }

    /**
     * Captures an authorized payment and returns it captured. Throws when the payment is not
     * this order's, is for a different amount or currency, or cannot be captured (failed,
     * refunded…). A {@link GatewayUnavailableException} means "unknown, try again later".
     */
    GatewayPayment ensureCaptured(Order order, GatewayPayment payment) {
        requireBelongsToOrder(order, payment);
        if (payment.authorized()) {
            log.info("[PAY] payment {} is authorized only; capturing {} paise for orderId={}",
                    payment.id(), order.getTotalAmount(), order.getId());
            payment = razorpayService.capture(payment.id(), order.getTotalAmount());
            requireBelongsToOrder(order, payment);
        }
        if (!payment.captured()) {
            log.warn("[PAY] payment {} for orderId={} is '{}', not captured — no booking",
                    payment.id(), order.getId(), payment.status());
            throw new IllegalStateException("Payment not captured (status=" + payment.status() + ")");
        }
        return payment;
    }

    private static void requireBelongsToOrder(Order order, GatewayPayment payment) {
        if (!payment.gatewayOrderId().equals(order.getGatewayOrderId())) {
            log.error("[PAY] payment {} belongs to gatewayOrderId={}, not this order's {} (orderId={})",
                    payment.id(), payment.gatewayOrderId(), order.getGatewayOrderId(), order.getId());
            throw new IllegalStateException("Payment does not belong to this order");
        }
        if (payment.amountPaise() != order.getTotalAmount() || !CURRENCY.equals(payment.currency())) {
            log.error("[PAY] payment {} is {} {} but orderId={} is {} INR",
                    payment.id(), payment.amountPaise(), payment.currency(), order.getId(), order.getTotalAmount());
            throw new IllegalStateException("Payment amount mismatch for order " + order.getId());
        }
    }

    /** Issues (or returns the already issued) booking for a captured payment of this order. */
    private Confirmed confirmCaptured(Order order, GatewayPayment payment) {
        Booking booking = finalizeService.finalizeOnline(order.getId(), payment.id(), payment.amountPaise());
        boolean hasRef = false;
        try {
            hasRef = finalizeService.recordBankReference(order.getId(), payment.bankReference());
        } catch (RuntimeException e) {
            // The booking is already issued; a missing reference must not undo the confirmation.
            log.warn("[PAY] bank reference not stored for orderId={}: {}", order.getId(), e.toString());
        }
        return new Confirmed(booking, !hasRef);
    }

    /**
     * Reconciliation for an order still awaiting payment: asks Razorpay for every payment on its
     * gateway order and confirms the booking if one is (or, once captured, becomes) captured.
     * Returns the booking id, or empty when nothing was paid.
     */
    public Optional<String> reconcile(Order order) {
        for (GatewayPayment payment : razorpayService.paymentsForOrder(order.getGatewayOrderId())) {
            if (!payment.captured() && !payment.authorized()) continue;
            try {
                Confirmed confirmed = confirmCaptured(order, ensureCaptured(order, payment));
                log.warn("[PAY] RECONCILED orderId={} paymentId={} -> {} (callback and webhook had not confirmed it)",
                        order.getId(), payment.id(), confirmed.booking().getBookingId());
                return Optional.of(confirmed.booking().getBookingId());
            } catch (IllegalArgumentException | IllegalStateException e) {
                log.error("[PAY] reconcile: payment {} for orderId={} not confirmed: {}",
                        payment.id(), order.getId(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Webhook: signature first; then captures, refunds and disputes for an order we know. */
    public WebhookResult handleWebhook(String body, String signatureHeader) {
        if (!razorpayService.verifyWebhookSignature(body, signatureHeader)) {
            return WebhookResult.rejected("invalid signature");
        }
        Optional<RazorpayWebhookPayload.Event> parsed = RazorpayWebhookPayload.parse(body);
        if (parsed.isEmpty()) {
            log.info("[PAY] webhook acknowledged, no payment in it");
            return WebhookResult.ignored();
        }
        RazorpayWebhookPayload.Event event = parsed.get();
        String type = event.type();
        // Terminal outcomes are acknowledged with 200 so Razorpay does not retry for 24h or
        // disable the webhook. Only a bad signature is answered with 400.
        try {
            switch (type) {
                case "payment.captured", "payment.authorized" -> {
                    return confirmFromWebhook(event.payment(), type);
                }
                case "refund.created", "refund.processed" -> {
                    return cancel(event.payment(), "Refunded (Razorpay refund " + event.subjectId() + ")", type);
                }
                case "payment.dispute.created" -> {
                    return cancel(event.payment(), "Payment disputed (Razorpay dispute " + event.subjectId() + ")", type);
                }
                case "refund.failed" -> {
                    log.error("[PAY] Razorpay refund {} FAILED for paymentId={}: the money was not returned, but the booking "
                                    + "was already cancelled when the refund was created — check the Razorpay dashboard",
                            event.subjectId(), event.payment().id());
                    return WebhookResult.ignored();
                }
                default -> {
                    log.info("[PAY] webhook {} acknowledged, no action", type);
                    return WebhookResult.ignored();
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("[PAY] webhook {} NOT applied for paymentId={} gatewayOrderId={}: {}",
                    type, event.payment().id(), event.payment().gatewayOrderId(), e.getMessage());
            return WebhookResult.ignored();
        } catch (GatewayUnavailableException e) {
            log.warn("[PAY] webhook {} for paymentId={}: Razorpay unreachable; the reconciliation job will retry",
                    type, event.payment().id());
            return WebhookResult.ignored();
        }
    }

    private WebhookResult confirmFromWebhook(GatewayPayment payment, String type) {
        Optional<Order> order = orderRepository.findByGatewayOrderId(payment.gatewayOrderId());
        if (order.isEmpty()) {
            log.warn("[PAY] webhook {} for unknown gatewayOrderId={} paymentId={}", type, payment.gatewayOrderId(), payment.id());
            return WebhookResult.ignored();
        }
        Confirmed confirmed = confirmCaptured(order.get(), ensureCaptured(order.get(), payment));
        log.info("[PAY] webhook {} finalised orderId={} -> {}", type, order.get().getId(), confirmed.booking().getBookingId());
        return WebhookResult.finalized(confirmed.booking().getBookingId());
    }

    private WebhookResult cancel(GatewayPayment payment, String reason, String type) {
        Optional<Order> order = payment.gatewayOrderId().isBlank()
                ? Optional.empty()
                : orderRepository.findByGatewayOrderId(payment.gatewayOrderId());
        if (order.isEmpty()) {
            order = paymentRepository.findByGatewayPaymentId(payment.id()).map(Payment::getOrder);
        }
        if (order.isEmpty()) {
            log.warn("[PAY] webhook {} for paymentId={} matches no order — nothing to cancel", type, payment.id());
            return WebhookResult.ignored();
        }
        String bookingId = bookingAdminService.cancelForPaymentEvent(order.get().getId(), reason);
        return WebhookResult.cancelled(bookingId);
    }
}
