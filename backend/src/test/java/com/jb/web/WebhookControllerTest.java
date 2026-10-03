package com.jb.web;

import com.jb.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebhookControllerTest {
    private final PaymentService payments = mock(PaymentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WebhookController(payments)).build();

    @Test
    void rejectedWebhookIs400() throws Exception {
        when(payments.handleWebhook(anyString(), any()))
                .thenReturn(PaymentService.WebhookResult.rejected("invalid signature"));

        mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "bad").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid signature"));
    }

    @Test
    void finalizedWebhookIsAcknowledgedWithBookingId() throws Exception {
        when(payments.handleWebhook(eq("{\"event\":\"payment.captured\"}"), eq("sig")))
                .thenReturn(PaymentService.WebhookResult.finalized("JB-0007"));

        mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "sig").content("{\"event\":\"payment.captured\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.bookingId").value("JB-0007"));
    }

    @Test
    void ignoredWebhookIsStillAcknowledgedSoRazorpayStopsRetrying() throws Exception {
        when(payments.handleWebhook(anyString(), any())).thenReturn(PaymentService.WebhookResult.ignored());

        mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "sig").content("{\"event\":\"payment.failed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));
    }

    @Test
    void finalizeDemoRouteNoLongerExistsOnThisController() throws Exception {
        mvc.perform(post("/api/webhooks/razorpay/finalize-demo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
