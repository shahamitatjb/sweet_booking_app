package com.jb.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jb.domain.Item;
import com.jb.domain.Order;
import com.jb.domain.Staff;
import com.jb.jobs.StuckPaymentJob;
import com.jb.repository.ItemRepository;
import com.jb.repository.StaffRepository;
import com.jb.security.JwtService;
import com.jb.service.OutboxWorker;
import com.jb.service.RazorpayService;
import com.jb.service.SettingsService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Boots the whole API (security filters, JPA, Flyway V1 migration) against a throwaway
 * Postgres 16 container. Only the outside world is faked: Razorpay order creation (no
 * network), and the two background jobs so they cannot race the assertions. Payment and
 * webhook signatures are checked for real with the test secrets below.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = {
        "jb.razorpay-key-id=" + IntegrationTestBase.RZP_KEY_ID,
        "jb.razorpay-key-secret=" + IntegrationTestBase.RZP_KEY_SECRET,
        "jb.razorpay-webhook-secret=" + IntegrationTestBase.RZP_WEBHOOK_SECRET,
        "jb.otp-provider=dev",
        "jb.email-provider=console",
        "jb.bootstrap-admin-emails=",
        "jb.booking-enabled-override=",
})
public abstract class IntegrationTestBase {
    static final String RZP_KEY_ID = "rzp_test_it";
    static final String RZP_KEY_SECRET = "it-key-secret";
    static final String RZP_WEBHOOK_SECRET = "it-webhook-secret";
    static final String CSRF = "it-csrf-token";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        // One container for every test class; Ryuk removes it when the JVM exits.
        POSTGRES.start();
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ItemRepository itemRepository;
    @Autowired protected StaffRepository staffRepository;
    @Autowired protected SettingsService settingsService;
    @Autowired protected JwtService jwtService;

    @SpyBean protected RazorpayService razorpayService;
    @MockBean OutboxWorker outboxWorker;
    @MockBean StuckPaymentJob stuckPaymentJob;

    private final AtomicInteger gatewaySeq = new AtomicInteger();

    protected Item ladoo;
    protected Item barfi;
    protected Staff admin;
    protected Staff counter;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE notification_outbox, bookings, payments, order_items, orders, otp_codes, "
                + "cash_handovers, audit_log, staff, items, catalogue_confirmations RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE counters SET value = 0 WHERE name = 'booking'");
        setSetting("booking_enabled", "true");
        setSetting("booking_window_open", "2000-01-01T00:00:00+05:30");
        setSetting("booking_window_close", "2100-01-01T00:00:00+05:30");
        setSetting("max_packets_per_item", "20");
        setSetting("max_packets_total", "50");
        setSetting("otp_required", "false");
        setSetting("otp_provider", "email");

        ladoo = itemRepository.save(item("Besan Ladoo", "500 g", 25000, "0.500", 1));
        barfi = itemRepository.save(item("Kaju Barfi", "250 g", 30000, "0.250", 2));
        admin = staffRepository.save(Staff.builder().email("admin@jb.test").name("Asha Admin")
                .role(Staff.Role.ADMIN).active(true).build());
        counter = staffRepository.save(Staff.builder().email("counter@jb.test").name("Chetan Counter")
                .role(Staff.Role.COUNTER).active(true).build());

        doAnswer(inv -> {
            Order o = inv.getArgument(0);
            Map<String, Object> gw = new LinkedHashMap<>();
            gw.put("gatewayOrderId", "order_IT" + gatewaySeq.incrementAndGet());
            gw.put("amount", o.getTotalAmount());
            gw.put("currency", "INR");
            gw.put("keyId", RZP_KEY_ID);
            gw.put("mode", "test");
            return gw;
        }).when(razorpayService).createOrder(any());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    protected void setSetting(String key, String value) {
        settingsService.put(key, "en", value);
    }

    protected static Item item(String name, String pack, int pricePaise, String kg, int sort) {
        Item i = new Item();
        i.setNameEn(name);
        i.setPackSize(pack);
        i.setPricePaise(pricePaise);
        i.setWeightKg(new BigDecimal(kg));
        i.setActive(true);
        i.setSortOrder(sort);
        return i;
    }

    protected Map<String, Object> customer() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Ravi Kumar");
        body.put("mobile", "9876543210");
        body.put("address", "12 Shivaji Nagar, Pune");
        body.put("pinCode", "411005");
        body.put("email", "ravi@example.com");
        return body;
    }

    protected Map<String, Object> orderBody(Object... itemIdQtyPairs) {
        Map<String, Object> body = customer();
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < itemIdQtyPairs.length; i += 2) {
            items.add(Map.of("itemId", itemIdQtyPairs[i], "quantity", itemIdQtyPairs[i + 1]));
        }
        body.put("items", items);
        body.put("acceptedTerms", true);
        return body;
    }

    // ── HTTP helpers ────────────────────────────────────────────────────

    protected ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** A signed-in staff request: Bearer JWT plus the double-submit CSRF cookie and header. */
    protected MockHttpServletRequestBuilder as(Staff staff, MockHttpServletRequestBuilder req) {
        return req.header("Authorization", "Bearer " + jwtService.issue(staff))
                .cookie(new Cookie("XSRF-TOKEN", CSRF))
                .header("X-XSRF-TOKEN", CSRF);
    }

    protected ResultActions staffPost(Staff staff, String path, Object body) throws Exception {
        return mvc.perform(as(staff, post(path)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions staffGet(Staff staff, String path) throws Exception {
        return mvc.perform(as(staff, get(path)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ── Online payment helpers ──────────────────────────────────────────

    /** Creates an online order and returns {orderId, gatewayOrderId, amountPaise}. */
    protected JsonNode createOnlineOrder(Object... itemIdQtyPairs) throws Exception {
        return body(postJson("/api/public/orders", orderBody(itemIdQtyPairs)));
    }

    /** What Razorpay Checkout hands the browser after a successful payment. */
    protected Map<String, Object> checkoutSuccess(JsonNode order, String paymentId) {
        String gatewayOrderId = order.at("/gateway/gatewayOrderId").asText();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", order.get("orderId").asText());
        body.put("razorpayOrderId", gatewayOrderId);
        body.put("razorpayPaymentId", paymentId);
        body.put("razorpaySignature", hmacHex(RZP_KEY_SECRET, gatewayOrderId + "|" + paymentId));
        return body;
    }

    protected String capturedWebhook(String gatewayOrderId, String paymentId, int amountPaise) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
                + "\"id\":\"" + paymentId + "\",\"order_id\":\"" + gatewayOrderId + "\","
                + "\"amount\":" + amountPaise + ",\"status\":\"captured\"}}}}";
    }

    protected ResultActions postWebhook(String rawBody, String signature) throws Exception {
        return mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                .content(rawBody).header("X-Razorpay-Signature", signature));
    }

    protected static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── DB assertions ───────────────────────────────────────────────────

    protected int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    protected String orderStatus(String orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, UUID.fromString(orderId));
    }

    protected long bookingCounter() {
        return jdbc.queryForObject("SELECT value FROM counters WHERE name = 'booking'", Long.class);
    }

    protected String qrToken(String bookingId) {
        return bookingId + "." + jdbc.queryForObject(
                "SELECT qr_signature FROM bookings WHERE booking_id = ?", String.class, bookingId);
    }

    protected List<String> auditActions() {
        return jdbc.queryForList("SELECT action FROM audit_log ORDER BY id", String.class);
    }
}
