package com.jb.web;

import com.jb.domain.Staff;
import com.jb.repository.BookingRepository;
import com.jb.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StaffLogoutTest {
    private final AuditService audit = mock(AuditService.class);
    private final StaffApiController controller = new StaffApiController(
            mock(OrderService.class), mock(BookingFinalizeService.class), mock(BookingRepository.class),
            mock(ReceiptService.class), audit);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void logoutClearsCookieAndAudits() throws Exception {
        Staff s = Staff.builder().id(5L).email("a@b.co").name("Amit").role(Staff.Role.ADMIN).active(true).build();
        var auth = new UsernamePasswordAuthenticationToken("a@b.co", null, List.of());
        auth.setDetails(s);
        SecurityContextHolder.getContext().setAuthentication(auth);

        mvc.perform(post("/api/staff/logout").cookie(new Cookie("jb_token", "x")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("jb_token", 0))
                .andExpect(cookie().path("jb_token", "/"));

        verify(audit).recordOutsideTx(eq("staff_signout"), eq("a@b.co"), eq(5L), eq("ADMIN"), anyMap(), any(), isNull());
    }

    @Test
    void logoutWithoutSessionStillSucceeds() throws Exception {
        mvc.perform(post("/api/staff/logout")).andExpect(status().isNoContent());
        verify(audit, never()).recordOutsideTx(any(), any(), any(), any(), anyMap(), any(), any());
    }
}
