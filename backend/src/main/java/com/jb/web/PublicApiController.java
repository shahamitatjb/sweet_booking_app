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
        out.put("bookingEnabled", settingsService.withinWindow(now));
        out.put("windowOpen", settingsService.windowOpen() == null ? "" : settingsService.windowOpen().toString());
        out.put("windowClose", settingsService.windowClose() == null ? "" : settingsService.windowClose().toString());
        out.put("items", items);
        return out;
    }

    @PostMapping("/otp/request")
    public ResponseEntity<?> requestOtp(@RequestBody Map<String, String> body) {
        String destination = body.getOrDefault("destination", "");
        String channelRaw = body.getOrDefault("channel", "email");
        OtpService.Channel channel;
        try {
            channel = OtpService.Channel.valueOf(channelRaw.toUpperCase());
        } catch (Exception e) {
            log.warn("[OTP] unknown channel '{}' requested — falling back to email", channelRaw);
            channel = OtpService.Channel.email;
        }
        String provider = settingsService.getOrDefault("otp_provider", "en", "email");
        if (channel == OtpService.Channel.sms && !"sms".equalsIgnoreCase(provider)) {
            log.warn("[OTP] SMS requested but otp_provider={} — 503 returned", provider);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "SMS OTP not enabled yet. Use email OTP."));
        }
        try {
            log.info("[OTP] issuing {} OTP to {} (provider={})", channel, destination, provider);
            var issued = otpService.issue(destination, channel);
            Map<String, Object> resp = new HashMap<>();
            resp.put("channel", issued.channel());
            resp.put("destination", issued.destination());
            if (issued.devCode() != null) {
                resp.put("devCode", issued.devCode());
            }
            log.info("[OTP] issued {} OTP to {} (devCodeReturned={})",
                    issued.channel(), issued.destination(), issued.devCode() != null);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            log.warn("[OTP] request rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.error("[OTP] request failed (503): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/orders/preview")
    public ResponseEntity<?> preview(@RequestBody Map<String, Object> body) {
        try {
            List<OrderService.CartLine> lines = parseLines(body);
            OrderService.Customer customer = parseCustomer(body);
            orderService.validateCustomer(customer);
            orderService.validatePins(customer.pinCode());
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
        } catch (Exception e) {
            log.warn("[BOOKING] preview rejected: {}", e.toString());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
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
            String otpCode = (String) body.getOrDefault("otpCode", "");
            String otpExpected = (String) body.getOrDefault("otpExpected", "");
            String mobile = String.valueOf(body.getOrDefault("mobile", ""));
            if (otpCode == null || otpCode.isBlank()) {
                log.warn("[BOOKING] create rejected: OTP required for {}", mobile);
                return ResponseEntity.badRequest().body(Map.of("error", "OTP required"));
            }
            if (otpExpected != null && !otpExpected.isBlank() && !otpService.verify(
                    mobile, OtpService.Channel.email, otpExpected, otpCode)) {
                log.warn("[BOOKING] create rejected: OTP mismatch for {}", mobile);
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid OTP"));
            }

            List<OrderService.CartLine> lines = parseLines(body);
            OrderService.Customer customer = parseCustomer(body);
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
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderId", order.getId().toString());
            out.put("amountPaise", order.getTotalAmount());
            out.put("gateway", gw);
            log.info("[BOOKING] step 3/3 gateway order created: orderId={} gatewayOrderId={} amountPaise={}",
                    order.getId(), gw.get("gatewayOrderId"), order.getTotalAmount());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("[BOOKING] createOrder failed unexpectedly", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private List<OrderService.CartLine> parseLines(Map<String, Object> body) {
        List<OrderService.CartLine> lines = new ArrayList<>();
        Object raw = body.get("items");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    long id = Long.parseLong(String.valueOf(m.get("itemId")));
                    Object qObj = m.get("quantity");
                    int qty = qObj == null ? 0 : Integer.parseInt(String.valueOf(qObj));
                    lines.add(new OrderService.CartLine(id, qty));
                }
            }
        }
        return lines;
    }

    private OrderService.Customer parseCustomer(Map<String, Object> body) {
        return new OrderService.Customer(
                str(body, "name"),
                str(body, "mobile"),
                str(body, "address"),
                str(body, "pinCode"),
                str(body, "email")
        );
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
