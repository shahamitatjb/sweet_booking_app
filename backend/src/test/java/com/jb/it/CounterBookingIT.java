package com.jb.it;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff at the registration table book for walk-in customers, paid by cash or UPI. */
class CounterBookingIT extends IntegrationTestBase {

    private Map<String, Object> counterBody(String method, Object... itemIdQtyPairs) {
        Map<String, Object> body = orderBody(itemIdQtyPairs);
        body.put("paymentMethod", method);
        return body;
    }

    @Test
    void cashBookingIsPaidAndAttributedToTheStaffMember() throws Exception {
        Map<String, Object> body = counterBody("cash", ladoo.getId(), 2);
        body.put("cashReceivedPaise", 50000);

        staffPost(counter, "/api/staff/counter/bookings", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0001"))
                .andExpect(jsonPath("$.amountPaise").value(50000))
                .andExpect(jsonPath("$.paymentMethod").value("cash"))
                .andExpect(jsonPath("$.takenBy").value("Chetan Counter"));

        assertThat(jdbc.queryForMap("SELECT status, channel, payment_method, created_by FROM orders"))
                .containsEntry("status", "paid")
                .containsEntry("channel", "counter")
                .containsEntry("payment_method", "cash")
                .containsEntry("created_by", counter.getId());
        assertThat(jdbc.queryForObject("SELECT method FROM payments", String.class)).isEqualTo("cash");
        assertThat(auditActions()).contains("counter_booking_issued");

        staffGet(counter, "/api/staff/my-bookings")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value("JB-0001"));
        staffGet(counter, "/api/staff/receipts/JB-0001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.takenByName").value("Chetan Counter"))
                .andExpect(jsonPath("$.channel").value("counter"));
    }

    @Test
    void upiBookingKeepsTheReference() throws Exception {
        Map<String, Object> body = counterBody("upi", barfi.getId(), 1);
        body.put("upiReference", "UPI-REF-42");

        staffPost(counter, "/api/staff/counter/bookings", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("upi"));
        assertThat(jdbc.queryForObject("SELECT upi_reference FROM orders", String.class)).isEqualTo("UPI-REF-42");
    }

    @Test
    void wrongCashAmountIsRejectedWithoutABooking() throws Exception {
        Map<String, Object> body = counterBody("cash", ladoo.getId(), 2);
        body.put("cashReceivedPaise", 40000);

        staffPost(counter, "/api/staff/counter/bookings", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cash received must equal exact amount"));
        assertThat(count("bookings")).isZero();
        assertThat(bookingCounter()).isZero();
    }

    @Test
    void counterAndOnlineBookingsShareOneSequence() throws Exception {
        staffPost(counter, "/api/staff/counter/bookings", counterBody("cash", ladoo.getId(), 1))
                .andExpect(jsonPath("$.bookingId").value("JB-0001"));
        var order = createOnlineOrder(barfi.getId(), 1);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT1"))
                .andExpect(jsonPath("$.bookingId").value("JB-0002"));
        staffPost(admin, "/api/staff/counter/bookings", counterBody("upi", ladoo.getId(), 1))
                .andExpect(jsonPath("$.bookingId").value("JB-0003"));
    }

    @Test
    void counterLimitsAndValidationMatchOnline() throws Exception {
        setSetting("max_packets_per_item", "3");

        staffPost(counter, "/api/staff/counter/bookings", counterBody("cash", ladoo.getId(), 4))
                .andExpect(status().isBadRequest());
        Map<String, Object> badMobile = counterBody("cash", ladoo.getId(), 1);
        badMobile.put("mobile", "123");
        staffPost(counter, "/api/staff/counter/bookings", badMobile)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("mobile"));
        assertThat(count("bookings")).isZero();
    }

    @Test
    void myBookingsOnlyShowsTheStaffMembersOwn() throws Exception {
        staffPost(counter, "/api/staff/counter/bookings", counterBody("cash", ladoo.getId(), 1)).andExpect(status().isOk());
        staffPost(admin, "/api/staff/counter/bookings", counterBody("cash", ladoo.getId(), 1)).andExpect(status().isOk());

        staffGet(counter, "/api/staff/my-bookings")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value("JB-0001"));
        staffGet(admin, "/api/staff/my-bookings")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value("JB-0002"));
    }
}
