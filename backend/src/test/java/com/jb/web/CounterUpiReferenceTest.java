package com.jb.web;

import com.jb.domain.Staff;
import com.jb.repository.BookingRepository;
import com.jb.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CounterUpiReferenceTest {
    private final OrderService orders = mock(OrderService.class);
    private final StaffApiController controller = new StaffApiController(
            orders, mock(BookingFinalizeService.class), mock(BookingRepository.class),
            mock(ReceiptService.class), mock(AuditService.class));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @BeforeEach
    void signIn() {
        Staff s = Staff.builder().id(5L).email("a@b.co").name("Amit").role(Staff.Role.ADMIN).active(true).build();
        var auth = new UsernamePasswordAuthenticationToken("a@b.co", null, List.of());
        auth.setDetails(s);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static String upiBody(String upiReferenceJson) {
        return """
                {"name":"Asha","mobile":"9876543210","address":"Pune","pinCode":"411001","paymentMethod":"upi",
                 "items":[{"itemId":1,"quantity":1}]%s}
                """.formatted(upiReferenceJson == null ? "" : ",\"upiReference\":" + upiReferenceJson);
    }

    @Test
    void upiBookingWithoutUtrIsRejectedBeforeAnOrderIsCreated() throws Exception {
        mvc.perform(post("/api/staff/counter/bookings").contentType(MediaType.APPLICATION_JSON).content(upiBody(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("upiReference"));
        verifyNoInteractions(orders);
    }

    @Test
    void upiBookingWithAUtrThatIsNotTwelveDigitsIsRejected() throws Exception {
        for (String bad : List.of("\"12345678901\"", "\"1234567890123\"", "\"12345678901A\"", "\"UPI-STAFF-CONFIRMED\"")) {
            mvc.perform(post("/api/staff/counter/bookings").contentType(MediaType.APPLICATION_JSON).content(upiBody(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.field").value("upiReference"));
        }
        verifyNoInteractions(orders);
    }
}
