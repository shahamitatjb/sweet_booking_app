package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.repository.BookingRepository;
import com.jb.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
            out.put("status", "invalid");
            out.put("message", "Invalid receipt");
            return out;
        }
        Booking booking = found.get();
        Order order = booking.getOrder();
        boolean sigOk = qrService.verify(
                booking.getBookingId(),
                order.getTotalAmount(),
                booking.getConfirmedAt().getEpochSecond(),
                signature);
        boolean paid = order.getStatus() == Order.Status.paid;
        if (!sigOk || !paid) {
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
        if (booking.getScanCount() > 1) {
            out.put("warning", "This receipt has been scanned before. Verify name and last 4 digits of mobile with the person.");
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> staffDetail(String bookingId) {
        Booking booking = bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Not found"));
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
