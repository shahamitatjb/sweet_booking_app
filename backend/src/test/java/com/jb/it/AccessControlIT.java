package com.jb.it;

import com.jb.domain.Staff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call what, through the real security filter chain. */
class AccessControlIT extends IntegrationTestBase {

    private static final List<String> ADMIN_GETS = List.of(
            "/api/admin/dashboard", "/api/admin/bookings", "/api/admin/settings", "/api/admin/staff");

    // Signed-out API calls are bounced to the Google sign-in page (oauth2Login's entry point)
    // rather than answered with 401; either way no data is returned.
    private static final String LOGIN_PAGE = "http://localhost:3000/staff/login";

    @Test
    void signedOutUsersCannotReachStaffOrAdminApis() throws Exception {
        for (String path : ADMIN_GETS) {
            mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl(LOGIN_PAGE));
        }
        mvc.perform(get("/api/staff/my-bookings")).andExpect(status().isFound()).andExpect(redirectedUrl(LOGIN_PAGE));
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
        // The counter screen needs the catalogue, read-only.
        staffGet(counter, "/api/admin/items").andExpect(status().isOk());
        staffPost(counter, "/api/admin/items", Map.of("nameEn", "Hack", "packSize", "1", "pricePaise", 1))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminsReachAdminApis() throws Exception {
        for (String path : ADMIN_GETS) {
            staffGet(admin, path).andExpect(status().isOk());
        }
        staffGet(admin, "/api/staff/me")
                .andExpect(jsonPath("$.email").value("admin@jb.test"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void deactivatedStaffLoseAccessEvenWithAValidToken() throws Exception {
        Staff gone = staffRepository.save(Staff.builder().email("gone@jb.test").role(Staff.Role.ADMIN).active(true).build());
        String token = jwtService.issue(gone);
        gone.setActive(false);
        staffRepository.save(gone);

        mvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isFound()).andExpect(redirectedUrl(LOGIN_PAGE));
    }

    @Test
    void tamperedTokensAreIgnored() throws Exception {
        mvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isFound()).andExpect(redirectedUrl(LOGIN_PAGE));
    }

    @Test
    void staffWritesWithoutTheCsrfTokenAreRefused() throws Exception {
        mvc.perform(post("/api/admin/settings")
                        .header("Authorization", "Bearer " + jwtService.issue(admin))
                        .contentType("application/json")
                        .content("[{\"key\":\"booking_enabled\",\"value\":\"false\"}]"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/public/config")).andExpect(jsonPath("$.bookingEnabled").value(true));
    }
}
