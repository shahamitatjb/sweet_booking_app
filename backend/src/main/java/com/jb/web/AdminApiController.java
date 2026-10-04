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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
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
    private final BookingAdminService bookingAdminService;
    private final BookedByResolver bookedByResolver;

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        List<Booking> bookings = bookingRepository.findAllExcludingStatus(Order.Status.voided);
        int total = bookings.size();
        int packets = 0;
        double kg = 0;
        int amount = 0;
        int online = 0, cash = 0, upi = 0;
        for (Booking b : bookings) {
            Order o = b.getOrder();
            packets += o.getTotalPackets();
            kg += o.getTotalWeightKg().doubleValue();
            amount += o.getTotalAmount();
            if (o.getChannel() == Order.Channel.online) online++;
            else if (o.getPaymentMethod() == Order.PaymentMethod.cash) cash++;
            else if (o.getPaymentMethod() == Order.PaymentMethod.upi) upi++;
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
        out.put("itemSummary", itemSummary());
        return out;
    }

    // One row per catalogue item (all time, voided excluded), plus anything ordered
    // under an item that has since been removed from the catalogue.
    private List<Map<String, Object>> itemSummary() {
        List<Object[]> stats = statsRepository.itemSummaryExcludingStatus(Order.Status.voided);
        Map<Long, long[]> byId = new LinkedHashMap<>();
        Map<String, long[]> byName = new LinkedHashMap<>();
        for (Object[] row : stats) {
            Long itemId = (Long) row[0];
            String name = String.valueOf(row[1]);
            long[] acc = itemId != null
                    ? byId.computeIfAbsent(itemId, k -> new long[2])
                    : byName.computeIfAbsent(name, k -> new long[2]);
            acc[0] += ((Number) row[3]).longValue();
            acc[1] += ((Number) row[4]).longValue();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        for (Item i : itemRepository.findAllByOrderBySortOrderAsc()) {
            long[] acc = byId.get(i.getId());
            if (acc == null) acc = byName.getOrDefault(i.getNameEn(), new long[2]);
            seenIds.add(i.getId());
            seenNames.add(i.getNameEn());
            out.add(itemSummaryRow(i.getId(), i.getNameEn(), i.getPackSize(), acc[0], acc[1]));
        }
        for (Object[] row : stats) {
            Long itemId = (Long) row[0];
            String name = String.valueOf(row[1]);
            boolean known = itemId != null ? seenIds.contains(itemId) : seenNames.contains(name);
            if (known) continue;
            if (itemId != null) seenIds.add(itemId);
            else seenNames.add(name);
            out.add(itemSummaryRow(itemId, name, String.valueOf(row[2]),
                    ((Number) row[3]).longValue(), ((Number) row[4]).longValue()));
        }
        return out;
    }

    private Map<String, Object> itemSummaryRow(Long itemId, String name, String packSize,
                                               long packets, long amountPaise) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", itemId);
        row.put("name", name);
        row.put("packSize", packSize);
        row.put("packets", packets);
        row.put("amountPaise", amountPaise);
        return row;
    }

    @GetMapping("/bookings")
    public List<Map<String, Object>> bookings(@RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "false") boolean voided) {
        List<Booking> source = voided
                ? bookingRepository.findAllWithStatus(Order.Status.voided)
                : bookingRepository.findAllExcludingStatus(Order.Status.voided);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Booking b : source) {
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
            row.put("takenBy", bookedByResolver.bookedBy(o));
            row.put("status", o.getStatus().name());
            row.put("voidReason", o.getVoidReason() == null ? "" : o.getVoidReason());
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
            if (o.getStatus() == Order.Status.voided) {
                log.info("[BOOKING] export skipped voided booking {}", bookingId);
                return null;
            }
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
                    bookedByResolver.bookedBy(o)
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

    @PostMapping("/bookings/{bookingId}/void")
    public ResponseEntity<?> voidBooking(@PathVariable String bookingId,
                                         @RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest request) {
        Staff staff = currentStaff();
        try {
            bookingAdminService.voidBooking(bookingId, body == null ? null : str(body, "reason"), staff, request);
            return ResponseEntity.ok(Map.of("ok", true, "status", Order.Status.voided.name()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[BOOKING] void rejected for {}: {}", bookingId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/bookings/delete-all")
    public ResponseEntity<?> deleteAllBookings(@RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest request) {
        Staff staff = currentStaff();
        try {
            Map<String, Integer> deleted = bookingAdminService.deleteAllBookings(
                    body == null ? null : str(body, "confirm"), staff, request);
            return ResponseEntity.ok(Map.of("ok", true, "deleted", deleted));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[BOOKING] delete-all rejected for {}: {}", staff.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/staff")
    public List<Map<String, Object>> staffList() {
        List<Staff> all = new ArrayList<>(staffRepository.findAll());
        all.sort(Comparator.comparing(Staff::getRole).thenComparing(Staff::getEmail,
                String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Staff s : all) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("email", s.getEmail());
            row.put("name", s.getName() == null ? "" : s.getName());
            row.put("role", s.getRole().name());
            row.put("active", s.isActive());
            out.add(row);
        }
        return out;
    }

    @PostMapping("/staff")
    public ResponseEntity<?> upsertStaff(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Staff actor = currentStaff();
        String email = str(body, "email");
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "A valid email address is required"));
        }
        email = email.trim().toLowerCase();
        Staff.Role role;
        try {
            role = Staff.Role.valueOf(String.valueOf(body.getOrDefault("role", "COUNTER")).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Role must be ADMIN or COUNTER"));
        }
        Object activeRaw = body.get("active");
        boolean active = activeRaw == null || Boolean.parseBoolean(String.valueOf(activeRaw));

        Long id = body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id")));
        Staff target;
        if (id != null) {
            target = staffRepository.findById(id).orElse(null);
            if (target == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Staff member not found"));
            }
            if (!target.getEmail().equalsIgnoreCase(email) && staffRepository.findByEmailIgnoreCase(email).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Another staff member already uses that email"));
            }
        } else {
            Optional<Staff> existing = staffRepository.findByEmailIgnoreCase(email);
            if (existing.isPresent()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "That email already belongs to a staff member (id " + existing.get().getId()
                                + ") — edit that row instead"));
            }
            target = new Staff();
        }

        boolean touchesSelf = actor.getId().equals(target.getId());
        if (touchesSelf && (!active || role != Staff.Role.ADMIN)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "You cannot deactivate your own account or change your own role — ask another admin"));
        }

        boolean isNew = target.getId() == null;
        target.setEmail(email);
        String name = str(body, "name");
        target.setName(name == null ? "" : name.trim());
        target.setRole(role);
        target.setActive(active);
        staffRepository.save(target);

        auditService.recordOutsideTx(isNew ? "staff_added" : "staff_edited",
                actor.getEmail(), actor.getId(), actor.getRole().name(),
                Map.of("staffId", target.getId(), "email", target.getEmail(),
                        "role", target.getRole().name(), "active", target.isActive()),
                request.getRemoteAddr(), null);
        log.info("[AUTH] staff {} by {} ({}): {} role={} active={}",
                isNew ? "created" : "updated", actor.getEmail(), actor.getId(),
                target.getEmail(), target.getRole(), target.isActive());
        return ResponseEntity.ok(Map.of("ok", true, "id", target.getId(), "email", target.getEmail()));
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
