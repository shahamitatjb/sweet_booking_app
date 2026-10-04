package com.jb.service;

import com.jb.domain.Counter;
import com.jb.domain.Staff;
import com.jb.repository.BookingRepository;
import com.jb.repository.CounterRepository;
import com.jb.repository.OrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookingAdminServiceDeleteAllTest {
    private final CounterRepository counterRepository = mock(CounterRepository.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AuditService auditService = mock(AuditService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final BookingAdminService service = new BookingAdminService(
            mock(BookingRepository.class), mock(OrderRepository.class),
            counterRepository, settingsService, jdbcTemplate, auditService);
    private final Staff admin = Staff.builder().id(1L).email("admin@x.co").role(Staff.Role.ADMIN).build();
    private final Counter counter = new Counter("booking", 42);

    @BeforeEach
    void setUp() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(counterRepository.findForUpdate("booking")).thenReturn(Optional.of(counter));
        when(jdbcTemplate.update(anyString())).thenReturn(3);
    }

    @Test
    void rejectsWrongConfirmation() {
        when(settingsService.bookingEnabled()).thenReturn(false);
        for (String c : new String[] {null, "", "delete all bookings", "DELETE"}) {
            assertThatThrownBy(() -> service.deleteAllBookings(c, admin, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DELETE ALL BOOKINGS");
        }
        verifyNoInteractions(jdbcTemplate, auditService);
    }

    @Test
    void rejectsWhileBookingsAreOpen() {
        when(settingsService.bookingEnabled()).thenReturn(true);
        assertThatThrownBy(() -> service.deleteAllBookings("DELETE ALL BOOKINGS", admin, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Turn bookings off");
        verifyNoInteractions(jdbcTemplate, auditService);
        verify(counterRepository, never()).save(any());
    }

    @Test
    void deletesChildTablesFirstResetsCounterAndAudits() {
        when(settingsService.bookingEnabled()).thenReturn(false);

        Map<String, Integer> deleted = service.deleteAllBookings(" DELETE ALL BOOKINGS ", admin, request);

        InOrder order = inOrder(counterRepository, jdbcTemplate);
        order.verify(counterRepository).findForUpdate("booking");
        order.verify(jdbcTemplate).update("DELETE FROM notification_outbox");
        order.verify(jdbcTemplate).update("DELETE FROM bookings");
        order.verify(jdbcTemplate).update("DELETE FROM payments");
        order.verify(jdbcTemplate).update("DELETE FROM order_items");
        order.verify(jdbcTemplate).update("DELETE FROM orders");
        order.verify(jdbcTemplate).update("DELETE FROM otp_codes");
        order.verify(jdbcTemplate).update("DELETE FROM cash_handovers");
        order.verify(counterRepository).save(counter);
        assertThat(counter.getValue()).isZero();
        assertThat(deleted).containsEntry("bookings", 3).containsEntry("orders", 3).hasSize(7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq("all_bookings_deleted"), eq("admin@x.co"), eq(1L), eq("ADMIN"),
                details.capture(), eq("10.0.0.1"), eq(null));
        assertThat(details.getValue()).containsEntry("previousBookingCounter", 42L).containsEntry("payments", 3);
    }
}
