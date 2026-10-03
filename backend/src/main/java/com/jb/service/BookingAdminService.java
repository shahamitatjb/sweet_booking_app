package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.domain.Staff;
import com.jb.repository.BookingRepository;
import com.jb.repository.OrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Admin-only lifecycle operations on a booking. Voiding is a soft delete: the
 * booking and its orders, order_items, payments, outbox and audit rows all stay in
 * the database as history, but the order is marked voided so it disappears from
 * dashboards, lists, totals and the receipt/QR checks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingAdminService {
    private final BookingRepository bookingRepository;
    private final OrderRepository orderRepository;
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
}
