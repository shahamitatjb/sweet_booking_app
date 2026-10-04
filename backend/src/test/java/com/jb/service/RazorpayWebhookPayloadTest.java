package com.jb.service;

import org.json.JSONObject;
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

    @Test
    void capturedPaymentCarriesTheUpiRrn() {
        String withRrn = CAPTURED.replace("\"status\":\"captured\"",
                "\"status\":\"captured\",\"acquirer_data\":{\"rrn\":\"412345678901\",\"upi_transaction_id\":\"ABC\"}");
        assertThat(RazorpayWebhookPayload.parseCaptured(withRrn).get().bankReference()).isEqualTo("412345678901");
        assertThat(RazorpayWebhookPayload.parseCaptured(CAPTURED).get().bankReference()).isNull();
    }

    @Test
    void bankReferenceFallsBackToNetbankingWalletThenCardReferences() {
        assertThat(RazorpayService.bankReference(new JSONObject(
                "{\"acquirer_data\":{\"bank_transaction_id\":\"NB998\"}}"))).contains("NB998");
        assertThat(RazorpayService.bankReference(new JSONObject(
                "{\"acquirer_data\":{\"transaction_id\":\"W77\"}}"))).contains("W77");
        assertThat(RazorpayService.bankReference(new JSONObject(
                "{\"acquirer_data\":{\"rrn\":null,\"auth_code\":\"A1B2\"}}"))).contains("A1B2");
        assertThat(RazorpayService.bankReference(new JSONObject("{\"acquirer_data\":{}}"))).isEmpty();
        assertThat(RazorpayService.bankReference(new JSONObject("{}"))).isEmpty();
    }

    @Test
    void parsesRefundAndDisputeEventsDownToTheirPayment() {
        var refund = RazorpayWebhookPayload.parse("""
                {"event":"refund.created","payload":{"refund":{"entity":{"id":"rfnd_1","payment_id":"pay_1"}},
                 "payment":{"entity":{"id":"pay_1","order_id":"order_1","amount":100,"currency":"INR","status":"refunded"}}}}
                """).orElseThrow();
        assertThat(refund.type()).isEqualTo("refund.created");
        assertThat(refund.subjectId()).isEqualTo("rfnd_1");
        assertThat(refund.payment().gatewayOrderId()).isEqualTo("order_1");

        var dispute = RazorpayWebhookPayload.parse("""
                {"event":"payment.dispute.created","payload":{"dispute":{"entity":{"id":"disp_1","payment_id":"pay_2"}}}}
                """).orElseThrow();
        assertThat(dispute.payment().id()).isEqualTo("pay_2");
        assertThat(dispute.payment().gatewayOrderId()).isEmpty();
        assertThat(dispute.subjectId()).isEqualTo("disp_1");
    }

    @Test
    void bodiesWithoutAPaymentAreNotUnderstood() {
        assertThat(RazorpayWebhookPayload.parse("{\"event\":\"order.paid\",\"payload\":{}}")).isEmpty();
        assertThat(RazorpayWebhookPayload.parse("not json")).isEmpty();
    }
}
