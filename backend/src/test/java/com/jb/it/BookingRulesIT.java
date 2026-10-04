package com.jb.it;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rules an online order must pass before any money is taken. Nothing may be saved when one fails. */
class BookingRulesIT extends IntegrationTestBase {

    @Test
    void bookingSwitchedOffRejectsOrders() throws Exception {
        setSetting("booking_enabled", "false");

        mvc.perform(get("/api/public/config")).andExpect(jsonPath("$.bookingEnabled").value(false));
        postJson("/api/public/orders", orderBody(ladoo.getId(), 1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Booking window is closed"));
        assertThat(count("orders")).isZero();
    }

    @Test
    void beforeTheWindowOpensOrdersAreRejected() throws Exception {
        setSetting("booking_window_open", "2099-01-01T00:00:00+05:30");

        mvc.perform(get("/api/public/config")).andExpect(jsonPath("$.bookingEnabled").value(false));
        postJson("/api/public/orders", orderBody(ladoo.getId(), 1)).andExpect(status().isForbidden());
        assertThat(count("orders")).isZero();
    }

    @Test
    void afterTheWindowClosesOrdersAreRejected() throws Exception {
        setSetting("booking_window_close", "2001-01-01T00:00:00+05:30");

        postJson("/api/public/orders", orderBody(ladoo.getId(), 1)).andExpect(status().isForbidden());
        assertThat(count("orders")).isZero();
    }

    @Test
    void termsMustBeAccepted() throws Exception {
        Map<String, Object> body = orderBody(ladoo.getId(), 1);
        body.put("acceptedTerms", false);

        postJson("/api/public/orders", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Terms must be accepted"));
        assertThat(count("orders")).isZero();
    }

    @Test
    void perItemLimitIsEnforced() throws Exception {
        setSetting("max_packets_per_item", "5");

        postJson("/api/public/orders", orderBody(ladoo.getId(), 6)).andExpect(status().isBadRequest());
        postJson("/api/public/orders", orderBody(ladoo.getId(), 5)).andExpect(status().isOk());
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void totalLimitIsEnforcedAcrossItems() throws Exception {
        setSetting("max_packets_total", "10");

        postJson("/api/public/orders", orderBody(ladoo.getId(), 6, barfi.getId(), 5)).andExpect(status().isBadRequest());
        postJson("/api/public/orders", orderBody(ladoo.getId(), 5, barfi.getId(), 5)).andExpect(status().isOk());
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void inactiveAndUnknownItemsAreRejected() throws Exception {
        barfi.setActive(false);
        itemRepository.save(barfi);

        postJson("/api/public/orders", orderBody(barfi.getId(), 1)).andExpect(status().isBadRequest());
        postJson("/api/public/orders", orderBody(999_999L, 1)).andExpect(status().isBadRequest());
        assertThat(count("orders")).isZero();
    }

    @Test
    void emptyCartAndBadQuantitiesAreRejected() throws Exception {
        Map<String, Object> empty = orderBody();
        postJson("/api/public/orders", empty)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("items"));
        postJson("/api/public/orders", orderBody(ladoo.getId(), 0)).andExpect(status().isBadRequest());
        postJson("/api/public/orders", orderBody(ladoo.getId(), -1)).andExpect(status().isBadRequest());
        postJson("/api/public/orders", orderBody(ladoo.getId(), 1.5)).andExpect(status().isBadRequest());
        assertThat(count("orders")).isZero();
    }

    @Test
    void invalidCustomerFieldsAreReportedByFieldName() throws Exception {
        for (var bad : List.of(
                Map.entry("name", "Al"),
                Map.entry("mobile", "12345"),
                Map.entry("address", "short"),
                Map.entry("pinCode", "4110"),
                Map.entry("email", "not-an-email"))) {
            Map<String, Object> body = orderBody(ladoo.getId(), 1);
            body.put(bad.getKey(), bad.getValue());
            postJson("/api/public/orders", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.field").value(bad.getKey()));
        }
        assertThat(count("orders")).isZero();
    }

    @Test
    void otpIsRequiredWhenSwitchedOnAndAcceptedOnlyOnce() throws Exception {
        setSetting("otp_required", "true");
        setSetting("otp_provider", "email");

        postJson("/api/public/orders", orderBody(ladoo.getId(), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("otp"));

        String code = body(postJson("/api/public/otp/request", Map.of("email", "ravi@example.com"))
                .andExpect(status().isOk())).get("devCode").asText();

        Map<String, Object> wrong = orderBody(ladoo.getId(), 1);
        wrong.put("otpCode", code.equals("000000") ? "111111" : "000000");
        postJson("/api/public/orders", wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("otp"));

        Map<String, Object> right = orderBody(ladoo.getId(), 1);
        right.put("otpCode", code);
        postJson("/api/public/orders", right).andExpect(status().isOk());
        // A code is single-use.
        postJson("/api/public/orders", right).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("otp"));
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void emailIsMandatoryWhenOtpGoesByEmail() throws Exception {
        setSetting("otp_required", "true");
        setSetting("otp_provider", "email");
        Map<String, Object> body = orderBody(ladoo.getId(), 1);
        body.put("email", "");

        postJson("/api/public/orders", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("email"));
    }

    @Test
    void otpRequestIsRefusedWhenOtpIsOff() throws Exception {
        postJson("/api/public/otp/request", Map.of("email", "ravi@example.com")).andExpect(status().isBadRequest());
        assertThat(count("otp_codes")).isZero();
    }
}
