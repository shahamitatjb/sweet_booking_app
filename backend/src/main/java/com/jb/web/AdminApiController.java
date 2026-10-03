package com.jb.web;

import com.jb.domain.*;
import com.jb.repository.*;
import com.jb.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminApiController {
    private static final DateTimeFormatter IST =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final BookingRepository bookingRepository;
    private final OrderRepository orderRepository;
    private final ItemRepository itemRepository;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final ExportService exportService;
    private final StaffRepository staffRepository;
    private final OrderItemStatsRepository statsRepository;

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        List<Booking> bookings = bookingRepository.findAllOrderByBookingNoDesc();
        int total = bookings.size();
        int packets = 0;
        double kg = 0;
        int amount = 0;
        int online = 0, cash = 0, upi = 0;
        Map<String, Integer> packetsPerItem = new LinkedHashMap<>();
        for (Booking b : bookings) {
            Order o = b.getOrder();
            packets += o.getTotalPackets();
            kg += o.getTotalWeightKg().doubleValue();
            amount += o.getTotalAmount();
            if (o.getChannel() == Order.Channel.online) online++;
            else if (o.getPaymentMethod() == Order.PaymentMethod.cash) cash++;
            else if (o.getPaymentMethod() == Order.PaymentMethod.upi) upi++;
        }
        Instant from = Instant.now().minusSeconds(365L * 24 * 3600);
        for (Object[] row : statsRepository.packetsPerItemSince(from)) {
            packetsPerItem.put(String.valueOf(row[0]) + " (" + row[1] + ")", ((Number) row[2]).intValue());
        }
        Map<String, Object> byChannel = new LinkedHashMap<>();
        byChannel.put("online", online);
        byChannel.put("counterCash", cash);
        byChannel.put("counterUpi", upi);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalBookings", total);
        out.put("totalPackets", packets);
        out.put("totalWeightKg", kg);
        out.put("totalCollectedPaise", amount);
        out.put("byChannel", byChannel);
        out.put("packetsPerItem", packetsPerItem);
        return out;
    }

    @GetMapping("/bookings")
    public List<Map<String, Object>> bookings(@RequestParam(required = false) String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Booking b : bookingRepository.findAllOrderByBookingNoDesc()) {
            Order o = b.getOrder();
            if (q != null && !q.isBlank()) {
                String needle = q.toLowerCase();
                if (!b.getBookingId().toLowerCase().contains(needle)
                        && !o.getCustomerName().toLowerCase().contains(needle)
                        && !o.getMobile().contains(needle)) {
                    continue;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bookingId", b.getBookingId());
            row.put("bookedAt", IST.format(b.getConfirmedAt()));
            row.put("name", o.getCustomerName());
            row.put("mobile", o.getMobile());
            row.put("address", o.getAddress());
            row.put("pin", o.getPinCode());
            row.put("packets", o.getTotalPackets());
            row.put("amountPaise", o.getTotalAmount());
            row.put("channel", o.getChannel().name());
            row.put("paymentMode", o.getPaymentMethod() == null ? "" : o.getPaymentMethod().name());
            row.put("status", o.getStatus().name());
            out.add(row);
        }
        return out;
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        if (body.get("bookingIds") instanceof List<?> list) {
            list.forEach(x -> ids.add(String.valueOf(x)));
        }
        byte[] xlsx = exportService.exportBookingsWithIds(ids, bookingId -> {
            Optional<Booking> ob = bookingRepository.findByBookingId(bookingId);
            if (ob.isEmpty()) return null;
            Booking b = ob.get();
            Order o = b.getOrder();
            return new ExportService.ExportRow(
                    b.getBookingId(),
                    IST.format(b.getConfirmedAt()),
                    o.getCustomerName(),
                    o.getMobile(),
                    o.getAddress(),
                    o.getPinCode(),
                    o.getTotalPackets(),
                    o.getTotalWeightKg().doubleValue(),
                    o.getTotalAmount(),
                    o.getChannel().name(),
                    o.getPaymentMethod() == null ? "" : o.getPaymentMethod().name(),
                    o.getCreatedBy() == null ? "" : String.valueOf(o.getCreatedBy())
            );
        });
        auditService.recordOutsideTx("excel_export", actorEmail(), actorId(), actorRole(),
                Map.of("count", ids.size()), null, null);
        return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=bookings.xlsx")
                .body(xlsx);
    }

    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody List<Map<String, String>> rows, HttpServletRequest request) {
        Staff staff = currentStaff();
        List<String> keys = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String key = row.get("key");
            String lang = row.getOrDefault("language", "en");
            String value = row.get("value");
            if (key == null) continue;
            settingsService.put(key, lang, value);
            keys.add(key);
        }
        auditService.recordOutsideTx("settings_changed", staff.getEmail(), staff.getId(), staff.getRole().name(),
                Map.of("keys", keys),
                request.getRemoteAddr(), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/settings")
    public Map<String, String> getSettings(@RequestParam(defaultValue = "en") String lang) {
        return settingsService.getForLanguage(lang);
    }

    @GetMapping("/items")
    public List<Map<String, Object>> items() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : itemRepository.findAllByOrderBySortOrderAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("nameEn", i.getNameEn());
            m.put("nameHi", i.getNameHi() == null ? "" : i.getNameHi());
            m.put("nameGu", i.getNameGu() == null ? "" : i.getNameGu());
            m.put("packSize", i.getPackSize());
            m.put("pricePaise", i.getPricePaise());
            m.put("weightKg", i.getWeightKg());
            m.put("active", i.isActive());
            m.put("sortOrder", i.getSortOrder());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/items")
    public ResponseEntity<?> upsertItem(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Staff staff = currentStaff();
        Long id = body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id")));
        Item item = id == null ? new Item() : itemRepository.findById(id).orElseGet(Item::new);
        item.setNameEn(String.valueOf(body.getOrDefault("nameEn", "")));
        item.setNameHi(str(body, "nameHi"));
        item.setNameGu(str(body, "nameGu"));
        item.setPackSize(String.valueOf(body.getOrDefault("packSize", "")));
        item.setPricePaise(Integer.parseInt(String.valueOf(body.getOrDefault("pricePaise", 0))));
        item.setWeightKg(new java.math.BigDecimal(String.valueOf(body.getOrDefault("weightKg", "0"))));
        item.setActive(Boolean.TRUE.equals(body.get("active")));
        item.setSortOrder(Integer.parseInt(String.valueOf(body.getOrDefault("sortOrder", 0))));
        itemRepository.save(item);
        auditService.recordOutsideTx(id == null ? "item_added" : "item_edited",
                staff.getEmail(), staff.getId(), staff.getRole().name(),
                Map.of("itemId", item.getId(), "name", item.getNameEn()),
                request.getRemoteAddr(), null);
        return ResponseEntity.ok(Map.of("id", item.getId()));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return List.of();
    }

    private Staff currentStaff() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof Staff s)) {
            throw new IllegalStateException("Staff required");
        }
        return s;
    }

    private String actorEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private Long actorId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof Staff s ? s.getId() : null;
    }

    private String actorRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getAuthorities().iterator().next().getAuthority();
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
