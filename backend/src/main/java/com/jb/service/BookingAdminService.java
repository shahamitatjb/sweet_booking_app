package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Counter;
import com.jb.domain.Order;
import com.jb.domain.Staff;
import com.jb.repository.BookingRepository;
import com.jb.repository.CounterRepository;
import com.jb.repository.OrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-only lifecycle operations on a booking. Voiding is a soft delete: the
 * booking and its orders, order_items, payments, outbox and audit rows all stay in
 * the database as history, but the order is marked voided so it disappears from
 * dashboards, lists, totals and the receipt/QR checks.
 *
 * <p>{@link #deleteAllBookings} is the one hard delete: it clears test data before
 * go-live and is only allowed while bookings are switched off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingAdminService {
    public static final String DELETE_ALL_CONFIRMATION = "DELETE ALL BOOKINGS";

    // Children before parents so foreign keys hold; order_items would also go via ON DELETE CASCADE.
    private static final String[] DELETE_ALL_TABLES = {
            "notification_outbox", "bookings", "payments", "order_items", "orders", "otp_codes", "cash_handovers"
    };

    private final BookingRepository bookingRepository;
    private final OrderRepository orderRepository;
    private final CounterRepository counterRepository;
    private final SettingsService settingsService;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    @Transactional
    public Booking voidBooking(String bookingId, String reason, Staff actor, HttpServletRequest request) {
        Booking booking = bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found"));
        Order order = booking.getOrder();
        if (order.getStatus() == Order.Status.voided) {
            throw new IllegalStateException("Booking is already voided");
        }
        String text = (reason == null || reason.isBlank())
                ? "Voided by " + actor.getEmail()
                : reason.trim();
        Order.Status before = order.getStatus();
        order.setStatus(Order.Status.voided);
        order.setVoidReason(text);
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);

        auditService.record("booking_voided", actor.getEmail(), actor.getId(), actor.getRole().name(),
                java.util.Map.of("bookingId", booking.getBookingId(), "reason", text,
                        "previousStatus", before.name(), "amountPaise", order.getTotalAmount()),
                request.getRemoteAddr(), null);
        log.warn("[BOOKING] voided by staffId={} ({}): {} previousStatus={} amountPaise={} reason=\"{}\"",
                actor.getId(), actor.getEmail(), booking.getBookingId(), before, order.getTotalAmount(), text);
        return booking;
    }

    /**
     * Payments that leave a trail to reconcile against: Razorpay (online) and counter UPI, which
     * lands in the bank. Counter cash does not, and voided bookings are out of the totals.
     */
    public static boolean isReconcilable(Order order) {
        if (order.getStatus() == Order.Status.voided) return false;
        return order.getChannel() == Order.Channel.online
                || order.getPaymentMethod() == Order.PaymentMethod.upi;
    }

    /** Marks (or un-marks) one booking as reconciled. The note is optional. */
    @Transactional
    public Booking setReconciled(String bookingId, boolean reconciled, String note, Staff actor,
                                 HttpServletRequest request) {
        Booking booking = bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found"));
        Order order = booking.getOrder();
        if (!isReconcilable(order)) {
            throw new IllegalStateException(order.getStatus() == Order.Status.voided
                    ? "Voided bookings cannot be reconciled"
                    : "Only online and counter UPI payments are reconciled");
        }
        String text = note == null || note.isBlank() ? null : note.trim();
        if (reconciled) {
            booking.setReconciledAt(Instant.now());
            booking.setReconciledBy(actor.getId());
            booking.setReconcileNote(text);
        } else {
            booking.setReconciledAt(null);
            booking.setReconciledBy(null);
            booking.setReconcileNote(null);
        }
        bookingRepository.save(booking);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("bookingId", booking.getBookingId());
        details.put("amountPaise", order.getTotalAmount());
        if (text != null && reconciled) details.put("note", text);
        auditService.record(reconciled ? "booking_reconciled" : "booking_unreconciled",
                actor.getEmail(), actor.getId(), actor.getRole().name(), details,
                request.getRemoteAddr(), null);
        log.info("[BOOKING] {} by staffId={} ({}): {}", reconciled ? "reconciled" : "unreconciled",
                actor.getId(), actor.getEmail(), booking.getBookingId());
        return booking;
    }

    /**
     * Marks several bookings reconciled with one shared optional note. Already reconciled,
     * unknown and non-reconcilable bookings are skipped and listed, not failed.
     */
    @Transactional
    public Map<String, Object> reconcileMany(List<String> bookingIds, String note, Staff actor,
                                             HttpServletRequest request) {
        List<String> done = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String id : new LinkedHashSet<>(bookingIds)) {
            Booking b = bookingRepository.findByBookingId(id).orElse(null);
            if (b == null || b.getReconciledAt() != null || !isReconcilable(b.getOrder())) {
                skipped.add(id);
                continue;
            }
            setReconciled(id, true, note, actor, request);
            done.add(id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reconciled", done);
        out.put("skipped", skipped);
        return out;
    }

    /**
     * Cancels an online order because Razorpay reports its money going back to the customer
     * (refund) or contested (dispute). Works before or after the booking was issued: a voided
     * order can never be confirmed later. Returns the booking id, or null if none was issued.
     */
    @Transactional
    public String cancelForPaymentEvent(UUID orderId, String reason) {
        // Same lock as BookingFinalizeService: a refund racing a confirmation is applied after it, never lost.
        counterRepository.findForUpdate(BookingFinalizeService.COUNTER_BOOKING)
                .orElseThrow(() -> new IllegalStateException("Counter row missing"));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        String bookingId = bookingRepository.findByOrderId(orderId).map(Booking::getBookingId).orElse(null);
        if (order.getStatus() == Order.Status.voided) {
            log.info("[BOOKING] gateway cancel skipped: order {} ({}) already voided", orderId, bookingId);
            return bookingId;
        }
        Order.Status before = order.getStatus();
        order.setStatus(Order.Status.voided);
        order.setVoidReason(reason);
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        auditService.record("booking_cancelled_by_gateway", "razorpay", null, "SYSTEM",
                java.util.Map.of("orderId", orderId.toString(), "bookingId", bookingId == null ? "" : bookingId,
                        "reason", reason, "previousStatus", before.name(), "amountPaise", order.getTotalAmount()),
                null, null);
        log.warn("[BOOKING] CANCELLED by Razorpay event: orderId={} bookingId={} previousStatus={} amountPaise={} reason=\"{}\"",
                orderId, bookingId, before, order.getTotalAmount(), reason);
        return bookingId;
    }

    /**
     * Permanently removes every order, booking, payment, outbox row, OTP code and cash
     * handover, and resets the booking counter so the next booking is JB-0001. The audit
     * log is kept and gains one entry recording who did it and how many rows went.
     */
    @Transactional
    public Map<String, Integer> deleteAllBookings(String confirmation, Staff actor, HttpServletRequest request) {
        if (!DELETE_ALL_CONFIRMATION.equals(confirmation == null ? null : confirmation.trim())) {
            throw new IllegalArgumentException("Type " + DELETE_ALL_CONFIRMATION + " to confirm");
        }
        if (settingsService.bookingEnabled()) {
            throw new IllegalStateException("Turn bookings off in Settings before deleting all bookings");
        }
        // Same lock booking finalization takes first, so a payment completing right now
        // either finishes before this runs or waits and then sees the cleared tables.
        Counter counter = counterRepository.findForUpdate(BookingFinalizeService.COUNTER_BOOKING)
                .orElseThrow(() -> new IllegalStateException("Counter row missing"));
        long previousCounter = counter.getValue();

        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (String table : DELETE_ALL_TABLES) {
            deleted.put(table, jdbcTemplate.update("DELETE FROM " + table));
        }
        counter.setValue(0);
        counterRepository.save(counter);

        Map<String, Object> details = new LinkedHashMap<>(deleted);
        details.put("previousBookingCounter", previousCounter);
        auditService.record("all_bookings_deleted", actor.getEmail(), actor.getId(), actor.getRole().name(),
                details, request.getRemoteAddr(), null);
        log.warn("[BOOKING] all bookings deleted by staffId={} ({}): {} previousBookingCounter={}",
                actor.getId(), actor.getEmail(), deleted, previousCounter);
        return deleted;
    }
}
