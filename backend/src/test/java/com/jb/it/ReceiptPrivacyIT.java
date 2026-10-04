package com.jb.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Booking IDs are sequential (JB-0001, JB-0002…), so nobody may read a customer's name,
 * mobile or address by guessing one. Customers use their private link; staff sign in.
 */
class ReceiptPrivacyIT extends IntegrationTestBase {
    private String token;

    @BeforeEach
    void paidOnlineBooking() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT1")).andExpect(status().isOk());
        token = receiptToken("JB-0001");
    }

    @Test
    void theBookingIdAloneDoesNotOpenTheReceipt() throws Exception {
        mvc.perform(get("/api/public/receipts/JB-0001"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("9876543210"))));
        mvc.perform(get("/api/public/receipts/JB-0001").param("t", "guess")).andExpect(status().isNotFound());
        // Unknown IDs answer exactly the same, so the response reveals nothing.
        mvc.perform(get("/api/public/receipts/JB-0999").param("t", token)).andExpect(status().isNotFound());
    }

    @Test
    void theQrImageNeedsTheTokenToo() throws Exception {
        // The QR encodes the token: serving it by ID alone would hand the token out.
        mvc.perform(get("/api/public/receipts/JB-0001/qr.png")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/receipts/JB-0001/qr.png").param("t", "guess")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/receipts/JB-0001/qr.png").param("t", token)).andExpect(status().isOk());
    }

    @Test
    void theCustomersPrivateLinkOpensTheirReceipt() throws Exception {
        mvc.perform(get("/api/public/receipts/JB-0001").param("t", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.mobile").value("9876543210"));
    }

    @Test
    void signedOutVisitorsCannotReadTheStaffDetail() throws Exception {
        var res = mvc.perform(get("/api/verify/JB-0001/staff")).andReturn().getResponse();
        assertThat(res.getStatus()).isNotEqualTo(200);
        assertThat(res.getContentAsString()).doesNotContain("Ravi Kumar").doesNotContain("9876543210");
    }

    @Test
    void staffStillSeeEverything() throws Exception {
        staffGet(counter, "/api/verify/JB-0001/staff")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Ravi Kumar"));
        staffGet(counter, "/api/staff/receipts/JB-0001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("12 Shivaji Nagar, Pune"));
        staffGet(admin, "/api/staff/receipts/JB-0001/qr.png").andExpect(status().isOk());
        staffGet(admin, "/api/staff/receipts/JB-0999/qr.png").andExpect(status().isNotFound());
    }

    @Test
    void qrVerificationStillWorksForAnyone() throws Exception {
        mvc.perform(get("/api/verify/" + qrToken("JB-0001"))).andExpect(jsonPath("$.status").value("genuine"));
    }
}
