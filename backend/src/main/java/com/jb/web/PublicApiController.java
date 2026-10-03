package com.jb.web;

import com.jb.domain.Item;
import com.jb.domain.Order;
import com.jb.repository.ItemRepository;
import com.jb.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicApiController {
    private final ItemRepository itemRepository;
    private final SettingsService settingsService;
    private final OtpService otpService;
    private final OrderService orderService;
    private final RazorpayService razorpayService;
    private final AuditService auditService;

    @GetMapping("/config")
    public Map<String, Object> config(@RequestParam(defaultValue = "en") String lang) {
        Map<String, String> texts = settingsService.getForLanguage(lang);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Item i : itemRepository.findByActiveTrueOrderBySortOrderAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("name", i.nameFor(lang));
            m.put("packSize", i.getPackSize());
            m.put("pricePaise", i.getPricePaise());
            m.put("weightKg", i.getWeightKg());
            items.add(m);
        }
        Instant now = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", texts.getOrDefault("title", "Diwali Sweets Booking"));
        out.put("subtitle", texts.getOrDefault("subtitle", ""));
        out.put("notice", texts.getOrDefault("notice", ""));
        out.put("terms", texts.getOrDefault("terms", ""));
        out.put("thankYou", texts.getOrDefault("thank_you", "Thank you for your continued support"));
        out.put("privacyNotice", texts.getOrDefault("privacy_notice", ""));
        out.put("maxPerItem", settingsService.maxPacketsPerItem());
        out.put("maxTotal", settingsService.maxPacketsTotal());
        out.put("otpRequired", settingsService.otpRequired());
        out.put("otpChannel", settingsService.otpChannel().name());
        out.put("bookingEnabled", settingsService.withinWindow(now));
        out.put("windowOpen", settingsService.windowOpen() == null ? "" : settingsService.windowOpen().toString());
        out.put("windowClose", settingsService.windowClose() == null ? "" : settingsService.windowClose().toString());
        out.put("items", items);
        return out;
    }

    /**
     * Sends an OTP when the admin has switched verification on. The server picks the
     * channel from the OTP provider setting: SMS to the mobile, otherwise email.
     */
    @PostMapping("/otp/request")
    public ResponseEntity<?> requestOtp(@RequestBody Map<String, Object> body) {
        if (!settingsService.otpRequired()) {
            log.warn("[OTP] request ignored: otp_required is off");
            return ResponseEntity.badRequest().body(Map.of("error", "OTP verification is not enabled"));
        }
        OtpService.Channel channel = settingsService.otpChannel();
        String destination = channel == OtpService.Channel.sms
                ? RequestParsing.str(body, "mobile") : RequestParsing.str(body, "email");
        try {
            log.info("[OTP] issuing {} OTP", channel);
            var issued = otpService.issue(destination == null ? "" : destination, channel);
            Map<String, Object> resp = new HashMap<>();
            resp.put("channel", issued.channel());
            resp.put("destination", issued.destination());
            if (issued.devCode() != null) {
                resp.put("devCode", issued.devCode());
            }
            log.info("[OTP] issued {} OTP (devCodeReturned={})", issued.channel(), issued.devCode() != null);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            log.warn("[OTP] request rejected: {}", e.getMessage());
            String field = channel == OtpService.Channel.sms ? "mobile" : "email";
            return ResponseEntity.badRequest().body(RequestParsing.errorBody(new FieldValidationException(field, e.getMessage())));
        } catch (IllegalStateException e) {
            log.error("[OTP] request failed (503): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/orders/preview")
    public ResponseEntity<?> preview(@RequestBody Map<String, Object> body) {
        try {
            List<OrderService.CartLine> lines = RequestParsing.parseLines(body);
            OrderService.Customer customer = RequestParsing.parseCustomer(body);
            orderService.validateCustomer(customer, emailRequiredForOtp());
            int maxPer = settingsService.maxPacketsPerItem();
            int maxTotal = settingsService.maxPacketsTotal();
            int packets = 0;
            int amount = 0;
            double kg = 0;
            List<Map<String, Object>> detail = new ArrayList<>();
            for (var line : lines) {
                var item = itemRepository.findById(line.itemId()).orElseThrow();
                packets += line.quantity();
                amount += item.getPricePaise() * line.quantity();
                kg += item.getWeightKg().doubleValue() * line.quantity();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", item.getNameEn());
                d.put("quantity", line.quantity());
                d.put("unitPrice", item.getPricePaise());
                d.put("lineAmount", item.getPricePaise() * line.quantity());
                detail.add(d);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("packets", packets);
            out.put("amountPaise", amount);
            out.put("weightKg", kg);
            out.put("maxPerItem", maxPer);
            out.put("maxTotal", maxTotal);
            out.put("items", detail);
            log.info("[BOOKING] preview: packets={} amountPaise={} weightKg={} lines={}",
                    packets, amount, kg, detail.size());
            return ResponseEntity.ok(out);
        } catch (RuntimeException e) {
            log.warn("[BOOKING] preview rejected: {}", e.toString());
            return ResponseEntity.badRequest().body(RequestParsing.errorBody(e));
        }
    }

    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            if (!settingsService.withinWindow(Instant.now())) {
                log.warn("[BOOKING] create rejected: booking window closed");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Booking window is closed"));
            }
            boolean accepted = Boolean.TRUE.equals(body.get("acceptedTerms"));
            if (!accepted) {
                log.warn("[BOOKING] create rejected: terms not accepted");
                return ResponseEntity.badRequest().body(Map.of("error", "Terms must be accepted"));
            }
            List<OrderService.CartLine> lines = RequestParsing.parseLines(body);
            OrderService.Customer customer = RequestParsing.parseCustomer(body);
            orderService.validateCustomer(customer, emailRequiredForOtp());
            requireOtpIfEnabled(body, customer);
            log.info("[BOOKING] step 1/3 createOnlineOrder: mobile={} name={} lines={} pin={}",
                    customer.mobile(), customer.name(), lines.size(), customer.pinCode());
            Order order = orderService.createOnlineOrder(lines, customer, true);
            log.info("[BOOKING] step 2/3 order persisted: orderId={} status={} amountPaise={} packets={}",
                    order.getId(), order.getStatus(), order.getTotalAmount(), order.getTotalPackets());
            auditService.recordOutsideTx("online_order_created", null, null, null,
                    Map.of("orderId", order.getId().toString(), "amount", order.getTotalAmount()),
                    request.getRemoteAddr(), null);

            Map<String, Object> gw;
            try {
                gw = razorpayService.createOrder(order);
            } catch (IllegalStateException e) {
                log.error("[BOOKING] step 3/3 Razorpay order create FAILED for orderId={} amountPaise={}: {}",
                        order.getId(), order.getTotalAmount(), e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", e.getMessage(), "orderId", order.getId().toString()));
            }
            order = orderService.markAwaitingPayment(order, String.valueOf(gw.get("gatewayOrderId")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderId", order.getId().toString());
            out.put("amountPaise", order.getTotalAmount());
            out.put("gateway", gw);
            log.info("[BOOKING] step 3/3 gateway order created: orderId={} gatewayOrderId={} amountPaise={}",
                    order.getId(), gw.get("gatewayOrderId"), order.getTotalAmount());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[BOOKING] createOrder rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(RequestParsing.errorBody(e));
        } catch (Exception e) {
            log.error("[BOOKING] createOrder failed unexpectedly (order may already exist)", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Could not create the booking — see API logs"));
        }
    }

    private boolean emailRequiredForOtp() {
        return settingsService.otpRequired() && settingsService.otpChannel() == OtpService.Channel.email;
    }

    /** With the admin switch on, the typed code must match the one issued to the customer's contact. */
    private void requireOtpIfEnabled(Map<String, Object> body, OrderService.Customer customer) {
        if (!settingsService.otpRequired()) return;
        OtpService.Channel channel = settingsService.otpChannel();
        String destination = channel == OtpService.Channel.sms ? customer.mobile() : customer.email();
        String otpCode = RequestParsing.str(body, "otpCode");
        if (otpCode == null || otpCode.isBlank()) {
            log.warn("[BOOKING] create rejected: OTP required");
            throw new FieldValidationException("otp", "OTP required");
        }
        if (!otpService.verify(destination, channel, otpCode)) {
            log.warn("[BOOKING] create rejected: OTP mismatch");
            throw new FieldValidationException("otp", "Invalid or expired OTP");
        }
    }
}
