package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.domain.OrderItem;
import com.jb.repository.BookingRepository;
import com.jb.repository.OrderItemRepository;
import com.jb.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ReceiptService {
    private static final DateTimeFormatter IST =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private final BookingRepository bookingRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SettingsService settingsService;
    private final QrService qrService;

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
        String verifyUrl = qrService.verificationUrl(
                System.getenv().getOrDefault("APP_BASE_URL", "http://localhost:3000"),
                booking.getBookingId(), booking.getQrSignature());
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
                settingsService.getOrDefault("terms", "en", ""),
                settingsService.getOrDefault("thank_you", "en", "Thank you for your continued support"),
                settingsService.getOrDefault("title", "en", "Diwali Sweets Booking"),
                verifyUrl,
                booking.getQrSignature(),
                order.getStatus().name(),
                order.getVoidReason()
        );
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
              .qr { width: 3cm; height: 3cm; }
              .muted { color: #333; font-size: 10px; }
            </style></head><body>
              <h1>%s</h1>
              %s
              <p><strong>Booking ID:</strong> %s<br/>
              <strong>Booked at (IST):</strong> %s<br/>
              <strong>Channel:</strong> %s · <strong>Payment:</strong> %s</p>
              %s
              <table><tr><th>Item</th><th>Pack</th><th>Packets</th><th>Amount</th></tr>%s</table>
              <p><strong>Total packets:</strong> %d<br/><strong>Total:</strong> %s</p>
              <p class="muted">Scan to verify: %s<br/>Signature: %s</p>
              <pre class="muted">%s</pre>
              <p>%s</p>
            </body></html>
            """.formatted(
                esc(v.title()), cancelled, esc(v.bookingId()), esc(v.bookedAtIst()),
                esc(v.channel()), esc(v.paymentMode() == null ? "-" : v.paymentMode()),
                pii, lines, v.totalPackets(), formatPaise(v.totalAmount()),
                esc(v.verifyUrl()), esc(v.signature()), esc(v.terms()), esc(v.thankYou()));
    }

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
            String terms,
            String thankYou,
            String title,
            String verifyUrl,
            String signature,
            String status,
            String voidReason
    ) {}
}
