package com.jb.service;

import com.jb.domain.*;
import com.jb.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Idempotent finalise: assign gapless JB- numbers only when payment is confirmed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingFinalizeService {
    public static final String COUNTER_BOOKING = "booking";

    private final OrderRepository orderRepository;
    private final CounterRepository counterRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final NotificationOutboxRepository outboxRepository;
    private final SettingsService settingsService;

    @Transactional
    public Booking finalizeOnline(UUID orderId, String gatewayPaymentId, int amountPaise) {
        return finalize(orderId, Order.PaymentMethod.gateway, gatewayPaymentId, amountPaise, null, null);
    }

    @Transactional
    public Booking finalizeCounterCash(UUID orderId, int amountPaise, Long staffId) {
        return finalize(orderId, Order.PaymentMethod.cash, null, amountPaise, null, staffId);
    }

    @Transactional
    public Booking finalizeCounterUpi(UUID orderId, int amountPaise, Long staffId, String upiRef) {
        return finalize(orderId, Order.PaymentMethod.upi, null, amountPaise, upiRef, staffId);
    }

    /**
     * Stores the bank reference of an online payment once it is known (Razorpay fetch or webhook).
     * Never overwrites one already stored. Returns true when the order has a reference afterwards.
     */
    @Transactional
    public boolean recordBankReference(UUID orderId, String bankReference) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.getUpiReference() != null && !order.getUpiReference().isBlank()) {
            return true;
        }
        if (bankReference == null || bankReference.isBlank()) {
            return false;
        }
        order.setUpiReference(bankReference);
        orderRepository.save(order);
        log.info("[PAY] bank reference stored for orderId={}: {}", orderId, bankReference);
        return true;
    }

    @Transactional
    public Booking finalize(UUID orderId,
                            Order.PaymentMethod method,
                            String gatewayPaymentId,
                            int amountPaise,
                            String upiReference,
                            Long staffId) {
        log.info("[BOOKING] finalize start: orderId={} method={} amountPaise={} gatewayPaymentId={} staffId={}",
                orderId, method, amountPaise, gatewayPaymentId, staffId);
        // Lock first: the webhook and the browser callback can arrive together for one order. Whoever
        // waits here re-reads the order and booking after the winner committed, and returns the
        // existing booking instead of tripping the bookings.order_id unique constraint.
        Counter counter = counterRepository.findForUpdate(COUNTER_BOOKING)
                .orElseThrow(() -> {
                    log.error("[BOOKING] finalize aborted: counter row '{}' missing", COUNTER_BOOKING);
                    return new IllegalStateException("Counter row missing");
                });
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("[BOOKING] finalize rejected: order not found {}", orderId);
                    return new IllegalArgumentException("Order not found: " + orderId);
                });

        Optional<Booking> existing = bookingRepository.findByOrderId(orderId);
        if (existing.isPresent() && order.getStatus() == Order.Status.paid) {
            log.info("[BOOKING] finalize idempotent: order {} already paid -> returning existing {}",
                    orderId, existing.get().getBookingId());
            return existing.get();
        }

        if (order.getStatus() == Order.Status.voided) {
            log.warn("[BOOKING] finalize rejected: order {} is voided", orderId);
            throw new IllegalStateException("Order voided");
        }
        if (order.getTotalAmount() != amountPaise) {
            log.error("[BOOKING] finalize rejected: amount mismatch for order {} (expected={} received={})",
                    orderId, order.getTotalAmount(), amountPaise);
            throw new IllegalStateException("Amount mismatch for order " + orderId);
        }

        long prev = counter.getValue();
        long next = prev + 1;
        counter.setValue(next);
        counterRepository.save(counter);

        String bookingId = formatBookingId(next);
        Instant now = Instant.now();
        String signature = QrService.sign(bookingId, order.getTotalAmount(), now.getEpochSecond(),
                settingsService.getOrDefault("qr_key_id", "en", "k1"));
        log.info("[BOOKING] sequence allocated: counter {} -> {} (bookingId={})", prev, next, bookingId);

        Booking booking = Booking.builder()
                .bookingNo(next)
                .bookingId(bookingId)
                .order(order)
                .confirmedAt(now)
                .qrSignature(signature)
                .qrKeyId(settingsService.getOrDefault("qr_key_id", "en", "k1"))
                .scanCount(0)
                .build();
        bookingRepository.save(booking);

        order.setStatus(Order.Status.paid);
        order.setPaymentMethod(method);
        order.setAcceptedTermsAt(order.getAcceptedTermsAt() != null ? order.getAcceptedTermsAt() : now);
        if (upiReference != null) {
            order.setUpiReference(upiReference);
        }
        if (staffId != null && order.getCreatedBy() == null) {
            order.setCreatedBy(staffId);
        }
        orderRepository.save(order);

        Payment payment = Payment.builder()
                .order(order)
                .method(method == Order.PaymentMethod.gateway ? Payment.Method.gateway : Payment.Method.cash)
                .gatewayPaymentId(gatewayPaymentId)
                .amount(amountPaise)
                .status(Payment.Status.captured)
                .capturedAt(now)
                .notes(upiReference)
                .build();
        paymentRepository.save(payment);

        enqueueNotifications(order, booking);

        auditService.record("online_booking_paid", null, staffId, staffId != null ? "COUNTER" : null,
                Map.of("bookingId", bookingId, "amount", amountPaise, "method", method.name()),
                null, null);

        log.info("[BOOKING] finalize DONE: orderId={} -> bookingId={} orderStatus={} method={} amountPaise={} confirmedAt={}",
                orderId, bookingId, order.getStatus(), method, amountPaise, now);
        return booking;
    }

    private void enqueueNotifications(Order order, Booking booking) {
        List<String> recipients = new ArrayList<>();
        if (order.getEmail() != null && !order.getEmail().isBlank()) {
            recipients.add(order.getEmail());
        }
        String cc = System.getenv("RECEIPT_CC_EMAILS");
        if (cc != null && !cc.isBlank()) {
            for (String e : cc.split(",")) {
                if (!e.isBlank()) recipients.add(e.trim());
            }
        }
        int receiptRows = 0;
        for (String to : recipients) {
            outboxRepository.save(NotificationOutbox.builder()
                    .bookingId(booking.getBookingId())
                    .orderId(order.getId())
                    .kind("receipt_pdf")
                    .toAddress(to)
                    .template("receipt_pdf")
                    .payload("{\"bookingId\":\"" + booking.getBookingId() + "\"}")
                    .status(NotificationOutbox.Status.pending)
                    .build());
            receiptRows++;
        }
        // Trustee booking alerts (email; WhatsApp deferred)
        int alertRows = 0;
        for (String to : trusteeAlertEmails()) {
            outboxRepository.save(NotificationOutbox.builder()
                    .bookingId(booking.getBookingId())
                    .orderId(order.getId())
                    .kind("booking_alert")
                    .toAddress(to)
                    .template("booking_alert")
                    .payload("{\"bookingId\":\"" + booking.getBookingId() + "\"}")
                    .status(NotificationOutbox.Status.pending)
                    .build());
            alertRows++;
        }
        log.info("[BOOKING] outbox enqueued for {}: receipts={} trusteeAlerts={} (picked up by OutboxWorker every 15s)",
                booking.getBookingId(), receiptRows, alertRows);
    }

    private List<String> trusteeAlertEmails() {
        String raw = System.getenv("TRUSTEE_ALERT_EMAILS");
        if (raw == null || raw.isBlank()) {
            String setting = settingsService.get("trustee_alert_emails", "en");
            raw = setting == null ? "" : setting;
        }
        List<String> list = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String e : raw.split(",")) {
                if (!e.isBlank()) list.add(e.trim());
            }
        }
        return list;
    }

    public static String formatBookingId(long no) {
        int pad = Math.max(4, String.valueOf(no).length());
        return "JB-" + String.format("%0" + pad + "d", no);
    }

    @Transactional(readOnly = true)
    public BigDecimal totalWeight(Order order) {
        return order.getTotalWeightKg();
    }
}
