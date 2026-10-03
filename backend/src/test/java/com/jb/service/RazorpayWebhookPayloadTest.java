package com.jb.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookPayloadTest {
    private static final String CAPTURED = """
            {"event":"payment.captured","payload":{"payment":{"entity":{
              "id":"pay_123","order_id":"order_abc","amount":45000,"currency":"INR","status":"captured"}}}}
            """;

    @Test
    void parsesCapturedPaymentFields() {
        var p = RazorpayWebhookPayload.parseCaptured(CAPTURED);
        assertThat(p).isPresent();
        assertThat(p.get().paymentId()).isEqualTo("pay_123");
        assertThat(p.get().gatewayOrderId()).isEqualTo("order_abc");
        assertThat(p.get().amountPaise()).isEqualTo(45000);
    }

    @Test
    void otherEventsAreEmpty() {
        String failed = CAPTURED.replace("payment.captured", "payment.failed");
        assertThat(RazorpayWebhookPayload.parseCaptured(failed)).isEmpty();
    }

    @Test
    void malformedJsonIsEmptyNotAnException() {
        assertThat(RazorpayWebhookPayload.parseCaptured("not json")).isEmpty();
        assertThat(RazorpayWebhookPayload.parseCaptured("{\"event\":\"payment.captured\"}")).isEmpty();
    }
}
