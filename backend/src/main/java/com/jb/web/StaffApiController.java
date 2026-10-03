package com.jb.web;

import com.jb.domain.*;
import com.jb.repository.*;
import com.jb.service.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    /** Ends the staff session: expires the token cookie and records the sign-out. Safe to call twice. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof Staff s) {
            auditService.recordOutsideTx("staff_signout", s.getEmail(), s.getId(), s.getRole().name(),
                    Map.of(), request.getRemoteAddr(), null);
            log.info("[AUTH] SIGN-OUT: staffId={} email={}", s.getId(), s.getEmail());
        }
        Cookie expired = new Cookie("jb_token", "");
        expired.setHttpOnly(true);
        expired.setSecure(request.isSecure());
        expired.setPath("/");
        expired.setMaxAge(0);
        response.addCookie(expired);
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/counter/bookings")
    public ResponseEntity<?> counterBooking(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            Staff staff = currentStaff();
            Order.PaymentMethod method = "cash".equalsIgnoreCase(String.valueOf(body.get("paymentMethod")))
                    ? Order.PaymentMethod.cash : Order.PaymentMethod.upi;
            log.info("[BOOKING] counter booking requested: staffId={} ({}) method={} mobile={}",
                    staff.getId(), staff.getEmail(), method, body.get("mobile"));

            List<OrderService.CartLine> lines = RequestParsing.parseLines(body);
            OrderService.Customer customer = RequestParsing.parseCustomer(body);

            Order order = orderService.createCounterOrder(lines, customer, method, staff.getId());

            Booking booking;
            if (method == Order.PaymentMethod.cash) {
                Object received = body.get("cashReceivedPaise");
                int amount = order.getTotalAmount();
                if (received != null && Integer.parseInt(String.valueOf(received)) != amount) {
                    log.warn("[BOOKING] counter cash rejected for orderId={}: received={} expected={}",
                            order.getId(), received, amount);
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
            out.put("takenBy", staff.getName() == null || staff.getName().isBlank() ? staff.getEmail() : staff.getName());
            log.info("[BOOKING] counter booking issued by staffId={} ({}): {} -> bookingId={} amountPaise={} method={}",
                    staff.getId(), staff.getEmail(), order.getId(), booking.getBookingId(),
                    order.getTotalAmount(), method);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[BOOKING] counter booking rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(RequestParsing.errorBody(e));
        } catch (Exception e) {
            log.error("[BOOKING] counter booking FAILED unexpectedly", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Could not issue the booking — see API logs"));
        }
    }

    @GetMapping("/my-bookings")
    public List<Map<String, Object>> myBookings() {
        Staff staff = currentStaff();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Booking b : bookingRepository.findAllExcludingStatus(Order.Status.voided)) {
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
            log.warn("[BOOKING] staff receipt lookup failed for {}: {}", bookingId, e.toString());
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/receipts/{bookingId}/pdf")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable String bookingId) {
        byte[] pdf = receiptService.pdfForBookingId(bookingId);
        log.info("[BOOKING] staff receipt PDF generated for {} ({} bytes)", bookingId, pdf.length);
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
