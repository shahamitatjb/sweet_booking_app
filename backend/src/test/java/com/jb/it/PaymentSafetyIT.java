package com.jb.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.jb.domain.Order;
import com.jb.repository.OrderRepository;
import com.jb.service.PaymentService;
import com.jb.service.RazorpayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A booking is confirmed only for money Razorpay has captured, and cancelled when it goes back. */
class PaymentSafetyIT extends IntegrationTestBase {
    @Autowired PaymentService paymentService;
    @Autowired OrderRepository orderRepository;

    private String gatewayOrderId(JsonNode order) {
        return order.at("/gateway/gatewayOrderId").asText();
    }

    private String refundWebhook(String event, String paymentId, String gatewayOrderId) {
        return "{\"event\":\"" + event + "\",\"payload\":{"
                + "\"refund\":{\"entity\":{\"id\":\"rfnd_1\",\"payment_id\":\"" + paymentId + "\",\"amount\":100}},"
                + "\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"order_id\":\"" + gatewayOrderId + "\","
                + "\"amount\":25000,\"currency\":\"INR\",\"status\":\"refunded\"}}}}";
    }

    private String disputeWebhook(String paymentId) {
        // Dispute bodies may carry only the dispute: the order is found through our payment row.
        return "{\"event\":\"payment.dispute.created\",\"payload\":{"
                + "\"dispute\":{\"entity\":{\"id\":\"disp_1\",\"payment_id\":\"" + paymentId + "\"}}}}";
    }

    @Test
    void authorizedOnlyPaymentIsCapturedBeforeTheBookingIsIssued() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        gatewayPayment(order, "pay_A", "authorized");

        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0001"));
        verify(razorpayService).capture(eq("pay_A"), eq(25000));
        assertThat(gatewayPayments.get("pay_A").status()).isEqualTo("captured");
    }

    @Test
    void validCheckoutSignatureForAFailedPaymentIssuesNoBooking() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        gatewayPayment(order, "pay_F", "failed");

        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_F"))
                .andExpect(status().isBadRequest());
        assertThat(count("bookings")).isZero();
        assertThat(bookingCounter()).isZero();
    }

    @Test
    void paymentOfAnotherOrderOrForLessMoneyIssuesNoBooking() throws Exception {
        JsonNode cheap = createOnlineOrder(ladoo.getId(), 1);
        JsonNode dear = createOnlineOrder(barfi.getId(), 3);
        // The cheap order's real payment, presented as the dear order's (signature is for the dear order).
        gatewayPayments.put("pay_X", new RazorpayService.GatewayPayment("pay_X", gatewayOrderId(cheap),
                "captured", 25000, "INR", null));
        postJson("/api/public/payments/verify", checkoutSuccess(dear, "pay_X")).andExpect(status().isBadRequest());

        gatewayPayments.put("pay_Y", new RazorpayService.GatewayPayment("pay_Y", gatewayOrderId(dear),
                "captured", 100, "INR", null));
        postJson("/api/public/payments/verify", checkoutSuccess(dear, "pay_Y")).andExpect(status().isBadRequest());

        assertThat(count("bookings")).isZero();
        assertThat(orderStatus(dear.get("orderId").asText())).isEqualTo("awaiting_payment");
    }

    @Test
    void razorpayUnreachableLeavesTheBookingPendingUntilTheWebhookConfirmsIt() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        var callback = checkoutSuccess(order, "pay_P");
        gatewayPayments.remove("pay_P"); // the fake Razorpay now cannot answer for it

        postJson("/api/public/payments/verify", callback)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.bookingId").doesNotExist());
        assertThat(count("bookings")).isZero();

        String hook = capturedWebhook(gatewayOrderId(order), "pay_P", 25000);
        postWebhook(hook, hmacHex(RZP_WEBHOOK_SECRET, hook)).andExpect(jsonPath("$.bookingId").value("JB-0001"));

        // The browser's next retry now gets the booking.
        postJson("/api/public/payments/verify", callback)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0001"));
    }

    @Test
    void refundAfterConfirmationCancelsTheBooking() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_R")).andExpect(status().isOk());

        String hook = refundWebhook("refund.created", "pay_R", gatewayOrderId(order));
        postWebhook(hook, hmacHex(RZP_WEBHOOK_SECRET, hook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0001"));

        assertThat(orderStatus(order.get("orderId").asText())).isEqualTo("voided");
        assertThat(jdbc.queryForObject("SELECT void_reason FROM orders", String.class)).startsWith("Refunded");
        mvc.perform(get("/api/verify/" + qrToken("JB-0001")))
                .andExpect(jsonPath("$.status").value("invalid"))
                .andExpect(jsonPath("$.message").value("Booking cancelled"));
    }

    @Test
    void refundBeforeConfirmationMeansTheOrderCanNeverBeConfirmed() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        String refund = refundWebhook("refund.processed", "pay_E", gatewayOrderId(order));
        postWebhook(refund, hmacHex(RZP_WEBHOOK_SECRET, refund)).andExpect(status().isOk());

        String captured = capturedWebhook(gatewayOrderId(order), "pay_E", 25000);
        postWebhook(captured, hmacHex(RZP_WEBHOOK_SECRET, captured))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").doesNotExist());
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_E")).andExpect(status().isBadRequest());

        assertThat(count("bookings")).isZero();
        assertThat(orderStatus(order.get("orderId").asText())).isEqualTo("voided");
    }

    @Test
    void disputeCancelsTheBookingFoundThroughItsPayment() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_D")).andExpect(status().isOk());

        String hook = disputeWebhook("pay_D");
        postWebhook(hook, hmacHex(RZP_WEBHOOK_SECRET, hook)).andExpect(status().isOk());

        assertThat(orderStatus(order.get("orderId").asText())).isEqualTo("voided");
        assertThat(jdbc.queryForObject("SELECT void_reason FROM orders", String.class)).startsWith("Payment disputed");
    }

    @Test
    void failedRefundChangesNothing() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_RF")).andExpect(status().isOk());

        String hook = refundWebhook("refund.failed", "pay_RF", gatewayOrderId(order));
        postWebhook(hook, hmacHex(RZP_WEBHOOK_SECRET, hook)).andExpect(status().isOk());

        assertThat(orderStatus(order.get("orderId").asText())).isEqualTo("paid");
    }

    @Test
    void reconciliationConfirmsAPaidOrderNobodyConfirmedAndCapturesAuthorizedOnes() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        gatewayPayment(order, "pay_failed_first", "failed");
        gatewayPayment(order, "pay_ok", "authorized");
        Order o = orderRepository.findById(UUID.fromString(order.get("orderId").asText())).orElseThrow();

        assertThat(paymentService.reconcile(o)).contains("JB-0001");
        verify(razorpayService).capture(eq("pay_ok"), anyInt());
        assertThat(orderStatus(order.get("orderId").asText())).isEqualTo("paid");
    }

    @Test
    void reconciliationLeavesUnpaidOrdersAlone() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        gatewayPayment(order, "pay_failed", "failed");
        Order o = orderRepository.findById(UUID.fromString(order.get("orderId").asText())).orElseThrow();

        assertThat(paymentService.reconcile(o)).isEmpty();
        assertThat(count("bookings")).isZero();
    }

    @Test
    void abandonedOrderIsMarkedFailedOnlyWhileStillAwaitingPayment() throws Exception {
        JsonNode paid = createOnlineOrder(ladoo.getId(), 1);
        postJson("/api/public/payments/verify", checkoutSuccess(paid, "pay_ok")).andExpect(status().isOk());
        JsonNode abandoned = createOnlineOrder(ladoo.getId(), 1);

        assertThat(orderRepository.markFailedIfStillAwaiting(UUID.fromString(paid.get("orderId").asText()), Instant.now()))
                .isZero();
        assertThat(orderRepository.markFailedIfStillAwaiting(UUID.fromString(abandoned.get("orderId").asText()), Instant.now()))
                .isEqualTo(1);
        assertThat(orderStatus(paid.get("orderId").asText())).isEqualTo("paid");
        assertThat(orderStatus(abandoned.get("orderId").asText())).isEqualTo("failed");

        // A late capture for the abandoned checkout still confirms it.
        String hook = capturedWebhook(gatewayOrderId(abandoned), "pay_late", 25000);
        postWebhook(hook, hmacHex(RZP_WEBHOOK_SECRET, hook)).andExpect(jsonPath("$.bookingId").value("JB-0002"));
    }
}
