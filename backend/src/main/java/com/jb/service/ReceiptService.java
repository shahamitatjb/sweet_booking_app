package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.domain.OrderItem;
import com.jb.repository.BookingRepository;
import com.jb.repository.OrderItemRepository;
import com.jb.repository.OrderRepository;
import com.jb.repository.StaffRepository;
import com.jb.domain.Staff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;

@Slf4j
@Service
public class ReceiptService {
    /** Stored on counter UPI bookings before the UTR was captured; not a real transaction id. */
    public static final String LEGACY_UPI_PLACEHOLDER = "UPI-STAFF-CONFIRMED";

    private static final DateTimeFormatter IST =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private final BookingRepository bookingRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SettingsService settingsService;
    private final QrService qrService;
    private final StaffRepository staffRepository;
    private final QrImageService qrImageService;
    private final String baseUrl;

    public ReceiptService(BookingRepository bookingRepository, OrderRepository orderRepository,
                          OrderItemRepository orderItemRepository, SettingsService settingsService,
                          QrService qrService, StaffRepository staffRepository, QrImageService qrImageService,
                          @org.springframework.beans.factory.annotation.Value("${jb.frontend-origin}") String frontendOrigin) {
        this.bookingRepository = bookingRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.settingsService = settingsService;
        this.qrService = qrService;
        this.staffRepository = staffRepository;
        this.qrImageService = qrImageService;
        // Verification links must open on the public site, which is the frontend origin.
        this.baseUrl = frontendOrigin.split(",")[0].trim();
    }

    public Optional<Booking> findByBookingId(String bookingId) {
        return bookingRepository.findByBookingId(bookingId);
    }

    @Transactional(readOnly = true)
    public ReceiptView view(String bookingId, boolean includePii) {
        Booking booking = bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> {
                    log.warn("[BOOKING] receipt view failed: booking {} not found", bookingId);
                    return new IllegalArgumentException("Booking not found");
                });
        Order order = booking.getOrder();
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        String verifyUrl = qrService.verificationUrl(baseUrl, booking.getBookingId(), booking.getQrSignature());
        return new ReceiptView(
                booking.getBookingId(),
                booking.getConfirmedAt(),
                IST.format(booking.getConfirmedAt()),
                order.getTotalPackets(),
                includePii ? order.getCustomerName() : null,
                includePii ? order.getMobile() : null,
                includePii ? order.getAddress() : null,
                includePii ? order.getPinCode() : null,
                items.stream().map(i -> new Line(i.getItemName(), i.getPackSize(), i.getQuantity(), i.getUnitPrice())).toList(),
                order.getTotalAmount(),
                order.getPaymentMethod() == null ? null : order.getPaymentMethod().name(),
                order.getChannel().name(),
                order.getCreatedBy(),
                takenByName(order.getCreatedBy()),
                settingsService.getOrDefault("terms", "en", ""),
                settingsService.getOrDefault("thank_you", "en", "Thank you for your continued support"),
                settingsService.getOrDefault("title", "en", "Diwali Sweets Booking"),
                verifyUrl,
                booking.getQrSignature(),
                order.getStatus().name(),
                order.getVoidReason(),
                transactionRef(order),
                transactionRefPending(order)
        );
    }

    /** UPI UTR (counter) or bank reference (online) for reconciliation; null when there is none to show. */
    public static String transactionRef(Order order) {
        String ref = order.getUpiReference();
        if (ref == null || ref.isBlank() || LEGACY_UPI_PLACEHOLDER.equals(ref)) return null;
        return ref;
    }

    /** Online payment whose bank reference has not arrived yet; the Razorpay webhook fills it in. */
    public static boolean transactionRefPending(Order order) {
        return order.getPaymentMethod() == Order.PaymentMethod.gateway && transactionRef(order) == null;
    }

    /** Display name of the staff member who issued a counter booking; falls back to their email. */
    private String takenByName(Long staffId) {
        if (staffId == null) return null;
        return staffRepository.findById(staffId)
                .map(s -> s.getName() == null || s.getName().isBlank() ? s.getEmail() : s.getName())
                .orElse(null);
    }

    /** PNG QR of the signed verification link; only for bookings that exist. */
    public byte[] qrPng(String bookingId) {
        Booking booking = bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found"));
        return qrImageService.png(qrService.verificationUrl(baseUrl, booking.getBookingId(), booking.getQrSignature()));
    }

    public byte[] pdfForBookingId(String bookingId) {
        ReceiptView v = view(bookingId, true);
        String html = receiptHtml(v);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useSVGDrawer(new BatikSVGDrawer());
            builder.withHtmlContent(html, null);
            builder.toStream(bos);
            builder.run();
            byte[] pdf = bos.toByteArray();
            log.info("[BOOKING] receipt PDF rendered for {} ({} bytes)", bookingId, pdf.length);
            return pdf;
        } catch (Exception e) {
            // Fallback: print-optimized HTML bytes if PDF engine fails in constrained env
            log.error("[BOOKING] PDF render FAILED for {} — falling back to HTML bytes", bookingId, e);
            return html.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String receiptHtml(ReceiptView v) {
        StringBuilder lines = new StringBuilder();
        for (Line l : v.items()) {
            lines.append("<tr><td>").append(esc(l.name())).append("</td><td>").append(esc(l.packSize()))
                    .append("</td><td>").append(l.quantity()).append("</td><td>")
                    .append(formatPaise(l.unitPrice() * l.quantity())).append("</td></tr>");
        }
        String pii = v.name() == null ? "" :
                "<p><strong>Name:</strong> " + esc(v.name()) + "<br/><strong>Mobile:</strong> " + esc(v.mobile()) +
                "<br/><strong>Address:</strong> " + esc(v.address()) + " — " + esc(v.pin()) + "</p>";
        String qr = "<img class=\"qr\" alt=\"Verification QR\" src=\"data:image/png;base64,"
                + java.util.Base64.getEncoder().encodeToString(qrImageService.png(v.verifyUrl())) + "\"/>";
        String takenBy = v.takenByName() == null ? "" :
                "<br/><strong>Booked by:</strong> " + esc(v.takenByName());
        String txnRef = v.transactionRef() != null
                ? "<br/><strong>Transaction ref:</strong> " + esc(v.transactionRef())
                : v.transactionRefPending() ? "<br/><strong>Transaction ref:</strong> " + TRANSACTION_REF_PENDING : "";
        String cancelled = !"voided".equals(v.status()) ? "" :
                "<p style=\"border:2px solid #b00000;font-weight:bold;padding:6px;\">CANCELLED"
                        + (v.voidReason() == null || v.voidReason().isBlank() ? "" : " — " + esc(v.voidReason()))
                        + "</p>";
        return """
            <html><head><meta charset="utf-8"/><style>
              @page { size: 14.9cm 21cm; margin: 0; }
              body { font-family: DejaVu Sans, sans-serif; color: #000; margin: 8mm; font-size: 11px; }
              h1 { font-size: 16px; margin: 0 0 4px 0; }
              table { width: 100%%; border-collapse: collapse; margin-top: 8px; }
              td, th { border: 1px solid #000; padding: 4px; text-align: left; }
              .qr { width: 3cm; height: 3cm; float: right; margin: 0 0 4px 6px; }
              .muted { color: #333; font-size: 10px; }
            </style></head><body>
              <h1>%s</h1>
              %s
              %s
              <p><strong>Booking ID:</strong> %s<br/>
              <strong>Booked at (IST):</strong> %s<br/>
              <strong>Channel:</strong> %s · <strong>Payment:</strong> %s%s%s</p>
              %s
              <table><tr><th>Item</th><th>Pack</th><th>Packets</th><th>Amount</th></tr>%s</table>
              <p><strong>Total packets:</strong> %d<br/><strong>Total:</strong> %s</p>
              <pre class="muted">%s</pre>
              <p>%s</p>
            </body></html>
            """.formatted(
                esc(v.title()), cancelled, qr, esc(v.bookingId()), esc(v.bookedAtIst()),
                esc(v.channel()), esc(v.paymentMode() == null ? "-" : v.paymentMode()), txnRef, takenBy,
                pii, lines, v.totalPackets(), formatPaise(v.totalAmount()),
                esc(v.terms()), esc(v.thankYou()));
    }

    public static final String TRANSACTION_REF_PENDING = "Pending — will be updated shortly";

    public static String formatPaise(int paise) {
        long rupees = paise / 100;
        int p = paise % 100;
        return String.format("₹%d.%02d", rupees, p);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record Line(String name, String packSize, int quantity, int unitPrice) {}

    public record ReceiptView(
            String bookingId,
            Instant confirmedAt,
            String bookedAtIst,
            int totalPackets,
            String name,
            String mobile,
            String address,
            String pin,
            List<Line> items,
            int totalAmount,
            String paymentMode,
            String channel,
            Long takenBy,
            String takenByName,
            String terms,
            String thankYou,
            String title,
            String verifyUrl,
            String signature,
            String status,
            String voidReason,
            String transactionRef,
            boolean transactionRefPending
    ) {}
}
