package com.jb.it;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin dashboard, void, export, settings, catalogue, staff and delete-all against the real database. */
class AdminOperationsIT extends IntegrationTestBase {

    private void counterCash(Object... itemIdQtyPairs) throws Exception {
        Map<String, Object> body = orderBody(itemIdQtyPairs);
        body.put("paymentMethod", "cash");
        staffPost(counter, "/api/staff/counter/bookings", body).andExpect(status().isOk());
    }

    @Test
    void dashboardTotalsAndPacketsToPrepare() throws Exception {
        counterCash(ladoo.getId(), 2, barfi.getId(), 1);
        var order = createOnlineOrder(ladoo.getId(), 3);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT1")).andExpect(status().isOk());
        createOnlineOrder(barfi.getId(), 5); // unpaid: must not count

        staffGet(admin, "/api/admin/dashboard")
                .andExpect(jsonPath("$.totalBookings").value(2))
                .andExpect(jsonPath("$.totalPackets").value(6))
                .andExpect(jsonPath("$.totalCollectedPaise").value(80000 + 75000))
                .andExpect(jsonPath("$.byChannel.online").value(1))
                .andExpect(jsonPath("$.byChannel.counterCash").value(1))
                .andExpect(jsonPath("$.itemSummary[0].name").value("Besan Ladoo"))
                .andExpect(jsonPath("$.itemSummary[0].packets").value(5))
                .andExpect(jsonPath("$.itemSummary[1].packets").value(1));
    }

    @Test
    void bookingSearchMatchesNameMobileAndId() throws Exception {
        counterCash(ladoo.getId(), 1);

        staffGet(admin, "/api/admin/bookings?q=ravi").andExpect(jsonPath("$.length()").value(1));
        staffGet(admin, "/api/admin/bookings?q=98765").andExpect(jsonPath("$.length()").value(1));
        staffGet(admin, "/api/admin/bookings?q=jb-0001").andExpect(jsonPath("$.length()").value(1));
        staffGet(admin, "/api/admin/bookings?q=nobody").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void voidingHidesTheBookingEverywhereButKeepsHistory() throws Exception {
        counterCash(ladoo.getId(), 2);
        counterCash(barfi.getId(), 1);
        String token = qrToken("JB-0001");

        staffPost(admin, "/api/admin/bookings/JB-0001/void", Map.of("reason", "Duplicate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"));

        staffGet(admin, "/api/admin/bookings")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value("JB-0002"));
        staffGet(admin, "/api/admin/bookings?voided=true")
                .andExpect(jsonPath("$[0].bookingId").value("JB-0001"))
                .andExpect(jsonPath("$[0].voidReason").value("Duplicate"));
        staffGet(admin, "/api/admin/dashboard")
                .andExpect(jsonPath("$.totalBookings").value(1))
                .andExpect(jsonPath("$.totalCollectedPaise").value(30000));
        mvc.perform(get("/api/verify/" + token))
                .andExpect(jsonPath("$.status").value("invalid"))
                .andExpect(jsonPath("$.message").value("Booking cancelled"));
        assertThat(count("bookings")).isEqualTo(2);
        assertThat(auditActions()).contains("booking_voided");

        staffPost(admin, "/api/admin/bookings/JB-0001/void", Map.of()).andExpect(status().isBadRequest());
        staffPost(admin, "/api/admin/bookings/JB-0404/void", Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void exportReturnsAnXlsxOfTheSelectedBookings() throws Exception {
        counterCash(ladoo.getId(), 1);

        byte[] xlsx = staffPost(admin, "/api/admin/export", Map.of("bookingIds", List.of("JB-0001")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx).startsWith((byte) 'P', (byte) 'K'); // zip container
        assertThat(auditActions()).contains("excel_export");
    }

    @Test
    void settingsSaveAndTakeEffectForCustomers() throws Exception {
        staffPost(superAdmin, "/api/admin/settings", List.of(
                Map.of("key", "booking_enabled", "language", "en", "value", "false"),
                Map.of("key", "title", "language", "en", "value", "Test Title")))
                .andExpect(status().isOk());

        staffGet(superAdmin, "/api/admin/settings").andExpect(jsonPath("$.title").value("Test Title"));
        mvc.perform(get("/api/public/config"))
                .andExpect(jsonPath("$.title").value("Test Title"))
                .andExpect(jsonPath("$.bookingEnabled").value(false));
        assertThat(auditActions()).contains("settings_changed");
    }

    @Test
    void catalogueChangesShowOnTheBookingPage() throws Exception {
        var id = body(staffPost(superAdmin, "/api/admin/items", Map.of(
                "nameEn", "Soan Papdi", "packSize", "400 g", "pricePaise", 20000,
                "weightKg", "0.4", "active", true, "sortOrder", 3))
                .andExpect(status().isOk())).get("id").asLong();

        mvc.perform(get("/api/public/config")).andExpect(jsonPath("$.items.length()").value(3));

        staffPost(superAdmin, "/api/admin/items", Map.of(
                "id", id, "nameEn", "Soan Papdi", "packSize", "400 g", "pricePaise", 20000,
                "weightKg", "0.4", "active", false, "sortOrder", 3))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/config")).andExpect(jsonPath("$.items.length()").value(2));
        assertThat(auditActions()).contains("item_added", "item_edited");
    }

    @Test
    void staffCanBeAddedButSuperAdminsCannotDemoteThemselves() throws Exception {
        staffPost(superAdmin, "/api/admin/staff", Map.of("email", "New.Person@JB.test", "role", "COUNTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new.person@jb.test"));
        staffPost(superAdmin, "/api/admin/staff", Map.of("email", "new.person@jb.test", "role", "COUNTER"))
                .andExpect(status().isBadRequest());
        staffPost(superAdmin, "/api/admin/staff", Map.of("id", superAdmin.getId(), "email", superAdmin.getEmail(),
                "role", "COUNTER", "active", true))
                .andExpect(status().isBadRequest());
        staffGet(superAdmin, "/api/admin/staff").andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void superAdminsCanGrantSuperAdminAndTreasurerRoles() throws Exception {
        staffPost(superAdmin, "/api/admin/staff", Map.of("email", "second.super@jb.test", "role", "SUPER_ADMIN"))
                .andExpect(status().isOk());
        staffPost(superAdmin, "/api/admin/staff", Map.of("email", "money@jb.test", "role", "treasurer"))
                .andExpect(status().isOk());
        staffPost(superAdmin, "/api/admin/staff", Map.of("email", "bad@jb.test", "role", "OWNER"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT role FROM staff WHERE email = 'second.super@jb.test'", String.class))
                .isEqualTo("SUPER_ADMIN");
        assertThat(jdbc.queryForObject("SELECT role FROM staff WHERE email = 'money@jb.test'", String.class))
                .isEqualTo("TREASURER");
        // Keeping your own role while editing your own name is fine.
        staffPost(superAdmin, "/api/admin/staff", Map.of("id", superAdmin.getId(), "email", superAdmin.getEmail(),
                "name", "Renamed", "role", "SUPER_ADMIN", "active", true))
                .andExpect(status().isOk());
    }

    @Test
    void deleteAllIsRefusedWhileBookingsAreOpen() throws Exception {
        counterCash(ladoo.getId(), 1);

        staffPost(superAdmin, "/api/admin/bookings/delete-all", Map.of("confirm", "DELETE ALL BOOKINGS"))
                .andExpect(status().isBadRequest());
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void deleteAllClearsTestDataAndRestartsAtJB0001() throws Exception {
        counterCash(ladoo.getId(), 1);
        var order = createOnlineOrder(barfi.getId(), 1);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT1")).andExpect(status().isOk());
        createOnlineOrder(ladoo.getId(), 1); // abandoned checkout
        setSetting("booking_enabled", "false");

        staffPost(superAdmin, "/api/admin/bookings/delete-all", Map.of("confirm", "WRONG"))
                .andExpect(status().isBadRequest());
        staffPost(superAdmin, "/api/admin/bookings/delete-all", Map.of("confirm", "DELETE ALL BOOKINGS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted.bookings").value(2))
                .andExpect(jsonPath("$.deleted.orders").value(3));

        for (String table : List.of("orders", "order_items", "payments", "bookings", "notification_outbox")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(bookingCounter()).isZero();
        assertThat(count("items")).isEqualTo(2);
        assertThat(count("staff")).isEqualTo(4);
        assertThat(auditActions()).contains("counter_booking_issued", "all_bookings_deleted");

        setSetting("booking_enabled", "true");
        counterCash(ladoo.getId(), 1);
        assertThat(jdbc.queryForObject("SELECT booking_id FROM bookings", String.class)).isEqualTo("JB-0001");
    }
}
