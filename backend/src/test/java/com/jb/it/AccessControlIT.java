package com.jb.it;

import com.jb.domain.Staff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call what, through the real security filter chain. */
class AccessControlIT extends IntegrationTestBase {

    private static final List<String> ADMIN_GETS = List.of(
            "/api/admin/dashboard", "/api/admin/bookings", "/api/admin/settings", "/api/admin/staff");
    private static final List<String> DASHBOARD_GETS = List.of("/api/admin/dashboard", "/api/admin/bookings");
    private static final List<String> SETTINGS_GETS = List.of("/api/admin/settings", "/api/admin/staff");

    @Test
    void signedOutUsersCannotReachStaffOrAdminApis() throws Exception {
        for (String path : ADMIN_GETS) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/staff/my-bookings")).andExpect(status().isUnauthorized());
    }

    @Test
    void counterStaffAreKeptOutOfAdminApis() throws Exception {
        for (String path : ADMIN_GETS) {
            staffGet(counter, path).andExpect(status().isForbidden());
        }
        staffPost(counter, "/api/admin/settings", List.of(Map.of("key", "booking_enabled", "value", "false")))
                .andExpect(status().isForbidden());
        staffPost(counter, "/api/admin/bookings/delete-all", Map.of("confirm", "DELETE ALL BOOKINGS"))
                .andExpect(status().isForbidden());
        staffPost(counter, "/api/admin/export", Map.of("bookingIds", List.of())).andExpect(status().isForbidden());
        // The counter screen needs the catalogue, read-only.
        staffGet(counter, "/api/admin/items").andExpect(status().isOk());
        staffPost(counter, "/api/admin/items", Map.of("nameEn", "Hack", "packSize", "1", "pricePaise", 1))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminsReachEverything() throws Exception {
        for (String path : ADMIN_GETS) {
            staffGet(superAdmin, path).andExpect(status().isOk());
        }
        staffGet(superAdmin, "/api/admin/audit").andExpect(status().isOk());
        staffGet(superAdmin, "/api/staff/me").andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
        staffPost(superAdmin, "/api/admin/settings", List.of(Map.of("key", "title", "value", "By super")))
                .andExpect(status().isOk());
    }

    @Test
    void adminsAndTreasurersSeeTheDashboardButNotSettings() throws Exception {
        for (Staff who : List.of(admin, treasurer)) {
            for (String path : DASHBOARD_GETS) {
                staffGet(who, path).andExpect(status().isOk());
            }
            staffGet(who, "/api/admin/items").andExpect(status().isOk());
            staffPost(who, "/api/admin/export", Map.of("bookingIds", List.of())).andExpect(status().isOk());
            for (String path : SETTINGS_GETS) {
                staffGet(who, path).andExpect(status().isForbidden());
            }
            staffPost(who, "/api/admin/settings", List.of(Map.of("key", "booking_enabled", "value", "false")))
                    .andExpect(status().isForbidden());
            staffPost(who, "/api/admin/items", Map.of("nameEn", "Hack", "packSize", "1", "pricePaise", 1))
                    .andExpect(status().isForbidden());
            staffPost(who, "/api/admin/staff", Map.of("email", "x@jb.test", "role", "SUPER_ADMIN"))
                    .andExpect(status().isForbidden());
            staffPost(who, "/api/admin/bookings/delete-all", Map.of("confirm", "DELETE ALL BOOKINGS"))
                    .andExpect(status().isForbidden());
        }
        staffGet(admin, "/api/staff/me")
                .andExpect(jsonPath("$.email").value("admin@jb.test"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        staffGet(treasurer, "/api/staff/me").andExpect(jsonPath("$.role").value("TREASURER"));
        mvc.perform(get("/api/public/config")).andExpect(jsonPath("$.bookingEnabled").value(true));
    }

    @Test
    void auditIsForSuperAdminsAndAdminsOnly() throws Exception {
        staffGet(admin, "/api/admin/audit").andExpect(status().isOk());
        staffGet(treasurer, "/api/admin/audit").andExpect(status().isForbidden());
        staffGet(counter, "/api/admin/audit").andExpect(status().isForbidden());
    }

    @Test
    void onlyTreasurersAndSuperAdminsMayReconcile() throws Exception {
        for (Staff who : List.of(admin, counter)) {
            staffPost(who, "/api/admin/bookings/JB-0001/reconcile", Map.of("reconciled", true))
                    .andExpect(status().isForbidden());
            staffPost(who, "/api/admin/bookings/reconcile", Map.of("bookingIds", List.of("JB-0001")))
                    .andExpect(status().isForbidden());
        }
        // Allowed through security; the booking simply does not exist.
        staffPost(treasurer, "/api/admin/bookings/JB-0404/reconcile", Map.of()).andExpect(status().isBadRequest());
        staffPost(superAdmin, "/api/admin/bookings/JB-0404/reconcile", Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void deactivatedStaffLoseAccessEvenWithAValidToken() throws Exception {
        Staff gone = staffRepository.save(Staff.builder().email("gone@jb.test").role(Staff.Role.ADMIN).active(true).build());
        String token = jwtService.issue(gone);
        gone.setActive(false);
        staffRepository.save(gone);

        mvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokensAreIgnored() throws Exception {
        mvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staffWritesWithoutTheCsrfTokenAreRefused() throws Exception {
        mvc.perform(post("/api/admin/settings")
                        .header("Authorization", "Bearer " + jwtService.issue(superAdmin))
                        .contentType("application/json")
                        .content("[{\"key\":\"booking_enabled\",\"value\":\"false\"}]"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/public/config")).andExpect(jsonPath("$.bookingEnabled").value(true));
    }
}
