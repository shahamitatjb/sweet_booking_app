package com.jb.it;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One mobile number cannot flood the system with unpaid orders. */
@TestPropertySource(properties = "jb.order-limit-per-mobile-per-hour=2")
class OrderRateLimitIT extends IntegrationTestBase {
    @Test
    void thirdOrderFromTheSameMobileWithinTheHourIsRefused() throws Exception {
        postJson("/api/public/orders", orderBody(ladoo.getId(), 1)).andExpect(status().isOk());
        postJson("/api/public/orders", orderBody(ladoo.getId(), 1)).andExpect(status().isOk());

        postJson("/api/public/orders", orderBody(ladoo.getId(), 1))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too many booking attempts. Please wait a while and try again."));
        assertThat(count("orders")).isEqualTo(2);
    }
}
