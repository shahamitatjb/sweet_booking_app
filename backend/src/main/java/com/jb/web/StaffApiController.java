package com.jb.web;

import com.jb.domain.*;
import com.jb.repository.*;
import com.jb.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/staff")
@RequiredArgsConstructor
public class StaffApiController {
    private final OrderService orderService;
    private final BookingFinalizeService finalizeService;
    private final BookingRepository bookingRepository;
    private final ReceiptService receiptService;
    private final AuditService auditService;

    @GetMapping("/me")
    public Map<String, Object> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getDetails() == null) {
            return Map.of("error", "unauthenticated");
        }
        Staff s = (Staff) auth.getDetails();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", s.getEmail());
        out.put("name", s.getName() == null ? "" : s.getName());
        out.put("role", s.getRole().name());
        out.put("id", s.getId());
        return out;
    }

    @PostMapping("/counter/bookings")
    public ResponseEntity<?> counterBooking(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            Staff staff = currentStaff();
            Order.PaymentMethod method = "cash".equalsIgnoreCase(String.valueOf(body.get("paymentMethod")))
                    ? Order.PaymentMethod.cash : Order.PaymentMethod.upi;

            List<OrderService.CartLine> lines = new ArrayList<>();
            if (body.get("items") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object qObj = m.get("quantity");
                        int qty = qObj == null ? 0 : Integer.parseInt(String.valueOf(qObj));
                        lines.add(new OrderService.CartLine(
                                Long.parseLong(String.valueOf(m.get("itemId"))),
                                qty));
                    }
                }
            }
            OrderService.Customer customer = new OrderService.Customer(
                    String.valueOf(body.getOrDefault("name", "")),
                    String.valueOf(body.getOrDefault("mobile", "")),
                    String.valueOf(body.getOrDefault("address", "")),
                    String.valueOf(body.getOrDefault("pinCode", "")),
                    body.get("email") == null ? null : String.valueOf(body.get("email")));

            Order order = orderService.createCounterOrder(lines, customer, method, staff.getId());

            Booking booking;
            if (method == Order.PaymentMethod.cash) {
                Object received = body.get("cashReceivedPaise");
                int amount = order.getTotalAmount();
                if (received != null && Integer.parseInt(String.valueOf(received)) != amount) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Cash received must equal exact amount"));
                }
                booking = finalizeService.finalizeCounterCash(order.getId(), amount, staff.getId());
            } else {
                booking = finalizeService.finalizeCounterUpi(order.getId(), order.getTotalAmount(), staff.getId(),
                        body.get("upiReference") == null ? null : String.valueOf(body.get("upiReference")));
            }
            auditService.recordOutsideTx("counter_booking_issued", staff.getEmail(), staff.getId(), staff.getRole().name(),
                    Map.of("bookingId", booking.getBookingId(), "amount", order.getTotalAmount(),
                            "method", method.name()),
                    request.getRemoteAddr(), null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bookingId", booking.getBookingId());
            out.put("amountPaise", order.getTotalAmount());
            out.put("paymentMethod", method.name());
            out.put("receiptUrl", "/receipt/" + booking.getBookingId());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/my-bookings")
    public List<Map<String, Object>> myBookings() {
        Staff staff = currentStaff();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Booking b : bookingRepository.findAllOrderByBookingNoDesc()) {
            Order o = b.getOrder();
            if (!Objects.equals(o.getCreatedBy(), staff.getId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bookingId", b.getBookingId());
            row.put("customerName", o.getCustomerName());
            row.put("mobile", o.getMobile());
            row.put("amountPaise", o.getTotalAmount());
            row.put("packets", o.getTotalPackets());
            row.put("paymentMethod", o.getPaymentMethod() == null ? "" : o.getPaymentMethod().name());
            row.put("confirmedAt", b.getConfirmedAt().toString());
            out.add(row);
        }
        return out;
    }

    @GetMapping("/receipts/{bookingId}")
    public ResponseEntity<?> receipt(@PathVariable String bookingId) {
        try {
            return ResponseEntity.ok(receiptService.view(bookingId, true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/receipts/{bookingId}/pdf")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable String bookingId) {
        byte[] pdf = receiptService.pdfForBookingId(bookingId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=" + bookingId + ".pdf")
                .body(pdf);
    }

    private Staff currentStaff() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof Staff s)) {
            throw new IllegalStateException("Staff required");
        }
        return s;
    }
}
