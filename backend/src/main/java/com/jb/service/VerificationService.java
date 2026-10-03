package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.repository.BookingRepository;
import com.jb.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {
    private final BookingRepository bookingRepository;
    private final OrderItemRepository orderItemRepository;
    private final QrService qrService;

    @Transactional
    public Map<String, Object> verify(String bookingId, String signature) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<Booking> found = bookingRepository.findByBookingId(bookingId);
        if (found.isEmpty()) {
            log.warn("[BOOKING] QR verify INVALID: booking {} not found", bookingId);
            out.put("status", "invalid");
            out.put("message", "Invalid receipt");
            return out;
        }
        Booking booking = found.get();
        Order order = booking.getOrder();
        if (order.getStatus() == Order.Status.voided) {
            log.warn("[BOOKING] QR verify VOIDED for {}", bookingId);
            out.put("status", "invalid");
            out.put("message", "Booking cancelled");
            out.put("bookingId", booking.getBookingId());
            return out;
        }
        boolean sigOk = qrService.verify(
                booking.getBookingId(),
                order.getTotalAmount(),
                booking.getConfirmedAt().getEpochSecond(),
                signature);
        boolean paid = order.getStatus() == Order.Status.paid;
        if (!sigOk || !paid) {
            log.warn("[BOOKING] QR verify INVALID for {}: signatureOk={} orderStatus={}",
                    bookingId, sigOk, order.getStatus());
            out.put("status", "invalid");
            out.put("message", "Invalid receipt");
            out.put("bookingId", booking.getBookingId());
            return out;
        }

        if (booking.getFirstScannedAt() == null) {
            booking.setFirstScannedAt(Instant.now());
        }
        booking.setScanCount(booking.getScanCount() + 1);
        bookingRepository.save(booking);

        int packets = order.getTotalPackets();
        out.put("status", "genuine");
        out.put("message", "Genuine, paid");
        out.put("bookingId", booking.getBookingId());
        out.put("totalPackets", packets);
        out.put("firstScannedAt", booking.getFirstScannedAt());
        out.put("scanCount", booking.getScanCount());
        log.info("[BOOKING] QR verify GENUINE for {}: scanCount={} packets={} (first scan {})",
                bookingId, booking.getScanCount(), packets, booking.getFirstScannedAt());
        if (booking.getScanCount() > 1) {
            log.warn("[BOOKING] QR {} scanned more than once (scanCount={}) — verify name/mobile at counter",
                    bookingId, booking.getScanCount());
            out.put("warning", "This receipt has been scanned before. Verify name and last 4 digits of mobile with the person.");
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> staffDetail(String bookingId) {
        Booking booking = bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> {
                    log.warn("[BOOKING] staff detail not found for {}", bookingId);
                    return new IllegalArgumentException("Not found");
                });
        Order order = booking.getOrder();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookingId", booking.getBookingId());
        out.put("status", order.getStatus().name());
        out.put("customerName", order.getCustomerName());
        out.put("mobile", order.getMobile());
        out.put("address", order.getAddress());
        out.put("pinCode", order.getPinCode());
        out.put("totalAmount", order.getTotalAmount());
        out.put("totalPackets", order.getTotalPackets());
        out.put("items", orderItemRepository.findByOrderId(order.getId()).stream()
                .map(i -> Map.of("name", i.getItemName(), "pack", i.getPackSize(), "qty", i.getQuantity()))
                .toList());
        return out;
    }
}
