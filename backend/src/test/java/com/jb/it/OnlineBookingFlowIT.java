package com.jb.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer books online: config → preview → order → Razorpay checkout → receipt → QR scan. */
class OnlineBookingFlowIT extends IntegrationTestBase {

    @Test
    void publicConfigListsActiveItemsAndOpenWindow() throws Exception {
        ladoo.setActive(true);
        var hidden = itemRepository.save(item("Old Item", "1 kg", 100, "1.000", 9));
        hidden.setActive(false);
        itemRepository.save(hidden);

        mvc.perform(get("/api/public/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingEnabled").value(true))
                .andExpect(jsonPath("$.maxPerItem").value(20))
                .andExpect(jsonPath("$.maxTotal").value(50))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Besan Ladoo"))
                .andExpect(jsonPath("$.items[1].name").value("Kaju Barfi"));
    }

    @Test
    void previewTotalsTheCartWithoutSavingAnything() throws Exception {
        postJson("/api/public/orders/preview", orderBody(ladoo.getId(), 2, barfi.getId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packets").value(3))
                .andExpect(jsonPath("$.amountPaise").value(2 * 25000 + 30000));
        assertThat(count("orders")).isZero();
    }

    @Test
    void checkoutCallbackTurnsAPaidOrderIntoBookingJB0001WithReceiptAndQr() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 2, barfi.getId(), 1);
        String orderId = order.get("orderId").asText();
        assertThat(order.get("amountPaise").asInt()).isEqualTo(80000);
        assertThat(order.at("/gateway/keyId").asText()).isEqualTo(RZP_KEY_ID);
        assertThat(orderStatus(orderId)).isEqualTo("awaiting_payment");
        assertThat(count("order_items")).isEqualTo(2);
        assertThat(count("bookings")).isZero();

        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0001"))
                .andExpect(jsonPath("$.receiptUrl").value("/receipt/JB-0001?t=" + receiptToken("JB-0001")));

        assertThat(orderStatus(orderId)).isEqualTo("paid");
        assertThat(bookingCounter()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payments", String.class)).isEqualTo("captured");
        assertThat(jdbc.queryForObject("SELECT amount FROM payments", Integer.class)).isEqualTo(80000);
        assertThat(jdbc.queryForObject("SELECT gateway_payment_id FROM payments", String.class)).isEqualTo("pay_IT1");
        // Customer gave an email, so a receipt is queued for the outbox worker.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE kind = 'receipt_pdf' "
                + "AND to_address = 'ravi@example.com'", Integer.class)).isEqualTo(1);
        assertThat(auditActions()).contains("online_order_created", "online_booking_paid");

        String t = receiptToken("JB-0001");
        mvc.perform(get("/api/public/receipts/JB-0001").param("t", t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0001"))
                .andExpect(jsonPath("$.totalAmount").value(80000))
                .andExpect(jsonPath("$.status").value("paid"));
        byte[] png = mvc.perform(get("/api/public/receipts/JB-0001/qr.png").param("t", t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void qrScanIsGenuineShowsTheBookingAndCountsScansSilently() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 3);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT1")).andExpect(status().isOk());
        String token = qrToken("JB-0001");

        mvc.perform(get("/api/verify/" + token))
                .andExpect(jsonPath("$.status").value("genuine"))
                .andExpect(jsonPath("$.totalPackets").value(3))
                .andExpect(jsonPath("$.scanCount").value(1))
                .andExpect(jsonPath("$.receipt.bookingId").value("JB-0001"))
                .andExpect(jsonPath("$.receipt.items[0].quantity").value(3))
                .andExpect(jsonPath("$.receipt.transactionRefPending").value(true))
                .andExpect(jsonPath("$.warning").doesNotExist());
        mvc.perform(get("/api/verify/" + token))
                .andExpect(jsonPath("$.status").value("genuine"))
                .andExpect(jsonPath("$.scanCount").value(2))
                .andExpect(jsonPath("$.warning").doesNotExist());
    }

    @Test
    void forgedOrUnknownQrTokensAreInvalid() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT1")).andExpect(status().isOk());

        mvc.perform(get("/api/verify/JB-0001.forgedsignature")).andExpect(jsonPath("$.status").value("invalid"));
        mvc.perform(get("/api/verify/JB-9999.anything")).andExpect(jsonPath("$.status").value("invalid"));
        mvc.perform(get("/api/verify/no-dot-here")).andExpect(jsonPath("$.status").value("invalid"));
        assertThat(jdbc.queryForObject("SELECT scan_count FROM bookings", Integer.class)).isZero();
    }

    @Test
    void badCheckoutSignatureCreatesNoBooking() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        var forged = checkoutSuccess(order, "pay_IT1");
        forged.put("razorpaySignature", "0".repeat(64));

        postJson("/api/public/payments/verify", forged)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Payment could not be confirmed"));
        assertThat(count("bookings")).isZero();
        assertThat(bookingCounter()).isZero();
        assertThat(orderStatus(order.get("orderId").asText())).isEqualTo("awaiting_payment");
    }

    @Test
    void webhookAfterCheckoutCallbackIsIdempotent() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 2);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT1"))
                .andExpect(jsonPath("$.bookingId").value("JB-0001"));

        String hook = capturedWebhook(order.at("/gateway/gatewayOrderId").asText(), "pay_IT1", 50000);
        postWebhook(hook, hmacHex(RZP_WEBHOOK_SECRET, hook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0001"));

        assertThat(count("bookings")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(bookingCounter()).isEqualTo(1);
    }

    @Test
    void webhookAloneConfirmsTheBookingWhenTheBrowserNeverCallsBack() throws Exception {
        JsonNode order = createOnlineOrder(barfi.getId(), 1);
        String hook = capturedWebhook(order.at("/gateway/gatewayOrderId").asText(), "pay_IT9", 30000);

        postWebhook(hook, hmacHex(RZP_WEBHOOK_SECRET, hook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0001"));
        assertThat(orderStatus(order.get("orderId").asText())).isEqualTo("paid");
    }

    @Test
    void webhookWithBadSignatureIsRejected() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        String hook = capturedWebhook(order.at("/gateway/gatewayOrderId").asText(), "pay_IT1", 25000);

        postWebhook(hook, hmacHex("wrong-secret", hook)).andExpect(status().isBadRequest());
        assertThat(count("bookings")).isZero();
    }

    @Test
    void webhookWithWrongAmountIsAcknowledgedButNotBooked() throws Exception {
        JsonNode order = createOnlineOrder(ladoo.getId(), 1);
        String hook = capturedWebhook(order.at("/gateway/gatewayOrderId").asText(), "pay_IT1", 100);

        postWebhook(hook, hmacHex(RZP_WEBHOOK_SECRET, hook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").doesNotExist());
        assertThat(count("bookings")).isZero();
        assertThat(bookingCounter()).isZero();
    }

    @Test
    void razorpayOutageLeavesNoBookingAndReturns503() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Razorpay order create failed: timeout"))
                .when(razorpayService).createOrder(org.mockito.ArgumentMatchers.any());

        postJson("/api/public/orders", orderBody(ladoo.getId(), 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.orderId").exists());
        assertThat(count("bookings")).isZero();
    }

    @Test
    void bookingIdsKeepCountingAcrossBookings() throws Exception {
        for (int i = 1; i <= 3; i++) {
            JsonNode order = createOnlineOrder(ladoo.getId(), 1);
            postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_IT" + i))
                    .andExpect(jsonPath("$.bookingId").value("JB-000" + i));
        }
        assertThat(bookingCounter()).isEqualTo(3);
    }
}
