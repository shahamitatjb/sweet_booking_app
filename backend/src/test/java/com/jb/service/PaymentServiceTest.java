package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private static final String KEY_SECRET = "test_secret_123";
    private static final String WEBHOOK_SECRET = "whsec_456";
    private static final UUID ORDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final BookingFinalizeService finalize = mock(BookingFinalizeService.class);
    private final RazorpayService razorpay = spy(new RazorpayService());
    private final PaymentService service = new PaymentService(orders, finalize, razorpay);

    PaymentServiceTest() {
        ReflectionTestUtils.setField(razorpay, "keyId", "rzp_test_key");
        ReflectionTestUtils.setField(razorpay, "keySecret", KEY_SECRET);
        ReflectionTestUtils.setField(razorpay, "webhookSecret", WEBHOOK_SECRET);
        doReturn(Optional.empty()).when(razorpay).fetchBankReference(any());
    }

    private static Order awaitingOrder() {
        return Order.builder().id(ORDER_ID).status(Order.Status.awaiting_payment)
                .gatewayOrderId("order_abc").totalAmount(45000).build();
    }

    private static Booking booking() {
        return Booking.builder().bookingId("JB-0007").build();
    }

    static String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- browser callback -------------------------------------------------

    @Test
    void validSignatureFinalizesWithTheOrdersOwnAmount() throws Exception {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());
        String sig = hmacHex(KEY_SECRET, "order_abc|pay_123");

        var confirmed = service.verifyAndFinalize(ORDER_ID, "order_abc", "pay_123", sig);

        assertThat(confirmed.booking().getBookingId()).isEqualTo("JB-0007");
        verify(finalize).finalizeOnline(ORDER_ID, "pay_123", 45000);
    }

    @Test
    void fetchedBankReferenceIsStoredAndNotPending() throws Exception {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());
        doReturn(Optional.of("412345678901")).when(razorpay).fetchBankReference("pay_123");
        when(finalize.recordBankReference(ORDER_ID, "412345678901")).thenReturn(true);

        var confirmed = service.verifyAndFinalize(ORDER_ID, "order_abc", "pay_123", hmacHex(KEY_SECRET, "order_abc|pay_123"));

        assertThat(confirmed.bankReferencePending()).isFalse();
        verify(finalize).recordBankReference(ORDER_ID, "412345678901");
    }

    @Test
    void failedBankReferenceFetchStillIssuesTheBookingAndMarksItPending() throws Exception {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());
        when(finalize.recordBankReference(ORDER_ID, null)).thenReturn(false);

        var confirmed = service.verifyAndFinalize(ORDER_ID, "order_abc", "pay_123", hmacHex(KEY_SECRET, "order_abc|pay_123"));

        assertThat(confirmed.booking().getBookingId()).isEqualTo("JB-0007");
        assertThat(confirmed.bankReferencePending()).isTrue();
    }

    @Test
    void wrongSignatureIsRejectedAndNothingIsFinalized() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));

        assertThatThrownBy(() -> service.verifyAndFinalize(ORDER_ID, "order_abc", "pay_123", "deadbeef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void gatewayOrderMismatchIsRejectedEvenWithAValidSignature() throws Exception {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
        String sig = hmacHex(KEY_SECRET, "order_other|pay_123");

        assertThatThrownBy(() -> service.verifyAndFinalize(ORDER_ID, "order_other", "pay_123", sig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order");
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void unknownOrderIsRejected() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyAndFinalize(ORDER_ID, "order_abc", "pay_123", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    // ---- webhook ----------------------------------------------------------

    private static final String CAPTURED = """
            {"event":"payment.captured","payload":{"payment":{"entity":{
              "id":"pay_123","order_id":"order_abc","amount":45000,"currency":"INR","status":"captured"}}}}
            """;

    @Test
    void capturedWebhookWithValidSignatureFinalizesBooking() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.of(awaitingOrder()));
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());

        var result = service.handleWebhook(CAPTURED, hmacHex(WEBHOOK_SECRET, CAPTURED));

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.FINALIZED);
        assertThat(result.bookingId()).isEqualTo("JB-0007");
    }

    @Test
    void capturedWebhookStoresTheBankReferenceFromAcquirerData() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.of(awaitingOrder()));
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());
        String withRrn = CAPTURED.replace("\"status\":\"captured\"",
                "\"status\":\"captured\",\"acquirer_data\":{\"rrn\":\"412345678901\"}");

        service.handleWebhook(withRrn, hmacHex(WEBHOOK_SECRET, withRrn));

        verify(finalize).recordBankReference(ORDER_ID, "412345678901");
    }

    @Test
    void webhookWithBadSignatureIsRejectedAndNothingIsFinalized() {
        var result = service.handleWebhook(CAPTURED, "bad");

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.REJECTED);
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void nonCapturedEventIsAcknowledgedButIgnored() throws Exception {
        String failed = CAPTURED.replace("payment.captured", "payment.failed");

        var result = service.handleWebhook(failed, hmacHex(WEBHOOK_SECRET, failed));

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.IGNORED);
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void capturedEventForUnknownOrderIsIgnored() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.empty());

        var result = service.handleWebhook(CAPTURED, hmacHex(WEBHOOK_SECRET, CAPTURED));

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.IGNORED);
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void amountMismatchIsNotFinalizedButIsAcknowledgedSoRazorpayStopsRetrying() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.of(awaitingOrder()));
        String shortPaid = CAPTURED.replace("45000", "100");
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 100))
                .thenThrow(new IllegalStateException("Amount mismatch for order " + ORDER_ID));

        var result = service.handleWebhook(shortPaid, hmacHex(WEBHOOK_SECRET, shortPaid));

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.IGNORED);
        assertThat(result.bookingId()).isNull();
    }

    @Test
    void onlyABadSignatureIsRejectedWith400Semantics() {
        var result = service.handleWebhook(CAPTURED, null);

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.REJECTED);
    }
}
