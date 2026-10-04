package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import com.jb.repository.BookingRepository;
import com.jb.repository.OrderRepository;
import com.jb.repository.PaymentRepository;
import com.jb.service.RazorpayService.GatewayPayment;
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
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PaymentRepository paymentRows = mock(PaymentRepository.class);
    private final BookingAdminService admin = mock(BookingAdminService.class);
    private final PaymentService service = new PaymentService(orders, finalize, razorpay, bookings, paymentRows, admin);

    PaymentServiceTest() {
        ReflectionTestUtils.setField(razorpay, "keyId", "rzp_test_key");
        ReflectionTestUtils.setField(razorpay, "keySecret", KEY_SECRET);
        ReflectionTestUtils.setField(razorpay, "webhookSecret", WEBHOOK_SECRET);
        when(bookings.findByOrderId(any())).thenReturn(Optional.empty());
    }

    private static Order awaitingOrder() {
        return Order.builder().id(ORDER_ID).status(Order.Status.awaiting_payment)
                .gatewayOrderId("order_abc").totalAmount(45000).build();
    }

    private static Booking booking() {
        return Booking.builder().bookingId("JB-0007").build();
    }

    private static GatewayPayment payment(String status) {
        return new GatewayPayment("pay_123", "order_abc", status, 45000, "INR", "412345678901");
    }

    private PaymentService.Confirmed callback() throws Exception {
        return service.verifyAndFinalize(ORDER_ID, "order_abc", "pay_123", hmacHex(KEY_SECRET, "order_abc|pay_123"));
    }

    static String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- browser callback -------------------------------------------------

    @Test
    void capturedPaymentIsConfirmedWithItsBankReference() throws Exception {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
        doReturn(payment("captured")).when(razorpay).fetchPayment("pay_123");
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());
        when(finalize.recordBankReference(ORDER_ID, "412345678901")).thenReturn(true);

        var confirmed = callback();

        assertThat(confirmed.booking().getBookingId()).isEqualTo("JB-0007");
        assertThat(confirmed.bankReferencePending()).isFalse();
        verify(razorpay, never()).capture(any(), anyInt());
    }

    @Test
    void authorizedPaymentIsCapturedFirst() throws Exception {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
        doReturn(payment("authorized")).when(razorpay).fetchPayment("pay_123");
        doReturn(payment("captured")).when(razorpay).capture("pay_123", 45000);
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());

        assertThat(callback().booking().getBookingId()).isEqualTo("JB-0007");
        verify(razorpay).capture("pay_123", 45000);
    }

    @Test
    void paymentThatIsNotCapturedIsNeverConfirmed() throws Exception {
        for (String status : new String[] {"failed", "created", "refunded"}) {
            when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
            doReturn(payment(status)).when(razorpay).fetchPayment("pay_123");

            assertThatThrownBy(this::callback).isInstanceOf(IllegalStateException.class);
        }
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void paymentForAnotherOrderAmountOrCurrencyIsRejected() throws Exception {
        GatewayPayment[] wrong = {
                new GatewayPayment("pay_123", "order_other", "captured", 45000, "INR", null),
                new GatewayPayment("pay_123", "order_abc", "captured", 100, "INR", null),
                new GatewayPayment("pay_123", "order_abc", "captured", 45000, "USD", null),
        };
        for (GatewayPayment p : wrong) {
            when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
            doReturn(p).when(razorpay).fetchPayment("pay_123");
            assertThatThrownBy(this::callback).isInstanceOf(IllegalStateException.class);
        }
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void razorpayUnreachableMeansPendingAndNoBooking() throws Exception {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));
        doThrow(new GatewayUnavailableException("down", null)).when(razorpay).fetchPayment("pay_123");

        var confirmed = callback();

        assertThat(confirmed.confirmationPending()).isTrue();
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void alreadyConfirmedOrderReturnsItsBookingWithoutAskingRazorpay() throws Exception {
        Order paid = awaitingOrder();
        paid.setStatus(Order.Status.paid);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(paid));
        when(bookings.findByOrderId(ORDER_ID)).thenReturn(Optional.of(booking()));

        assertThat(callback().booking().getBookingId()).isEqualTo("JB-0007");
        verify(razorpay, never()).fetchPayment(any());
    }

    @Test
    void wrongSignatureIsRejectedAndRazorpayIsNotEvenAsked() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(awaitingOrder()));

        assertThatThrownBy(() -> service.verifyAndFinalize(ORDER_ID, "order_abc", "pay_123", "deadbeef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
        verify(razorpay, never()).fetchPayment(any());
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
              "id":"pay_123","order_id":"order_abc","amount":45000,"currency":"INR","status":"captured",
              "acquirer_data":{"rrn":"412345678901"}}}}}
            """;

    private PaymentService.WebhookResult hook(String body) throws Exception {
        return service.handleWebhook(body, hmacHex(WEBHOOK_SECRET, body));
    }

    @Test
    void capturedWebhookFinalizesWithoutCallingRazorpay() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.of(awaitingOrder()));
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());

        var result = hook(CAPTURED);

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.FINALIZED);
        assertThat(result.bookingId()).isEqualTo("JB-0007");
        verify(finalize).recordBankReference(ORDER_ID, "412345678901");
        verify(razorpay, never()).capture(any(), anyInt());
    }

    @Test
    void authorizedWebhookCapturesThenFinalizes() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.of(awaitingOrder()));
        doReturn(payment("captured")).when(razorpay).capture("pay_123", 45000);
        when(finalize.finalizeOnline(ORDER_ID, "pay_123", 45000)).thenReturn(booking());

        var result = hook(CAPTURED.replace("payment.captured", "payment.authorized")
                .replace("\"status\":\"captured\"", "\"status\":\"authorized\""));

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.FINALIZED);
        verify(razorpay).capture("pay_123", 45000);
    }

    @Test
    void refundAndDisputeWebhooksCancelTheOrder() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.of(awaitingOrder()));
        when(admin.cancelForPaymentEvent(eq(ORDER_ID), any())).thenReturn("JB-0007");
        String refund = """
                {"event":"refund.created","payload":{"refund":{"entity":{"id":"rfnd_9","payment_id":"pay_123","amount":100}},
                 "payment":{"entity":{"id":"pay_123","order_id":"order_abc","amount":45000,"currency":"INR","status":"captured"}}}}
                """;

        var result = hook(refund);

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.CANCELLED);
        verify(admin).cancelForPaymentEvent(ORDER_ID, "Refunded (Razorpay refund rfnd_9)");

        Order viaPayment = awaitingOrder();
        when(paymentRows.findByGatewayPaymentId("pay_777"))
                .thenReturn(Optional.of(com.jb.domain.Payment.builder().order(viaPayment).build()));
        hook("""
                {"event":"payment.dispute.created","payload":{"dispute":{"entity":{"id":"disp_1","payment_id":"pay_777"}}}}
                """);
        verify(admin).cancelForPaymentEvent(ORDER_ID, "Payment disputed (Razorpay dispute disp_1)");
    }

    @Test
    void failedRefundCancelsNothing() throws Exception {
        String failed = """
                {"event":"refund.failed","payload":{"refund":{"entity":{"id":"rfnd_9","payment_id":"pay_123"}}}}
                """;
        assertThat(hook(failed).outcome()).isEqualTo(PaymentService.WebhookOutcome.IGNORED);
        verify(admin, never()).cancelForPaymentEvent(any(), any());
    }

    @Test
    void webhookWithBadSignatureIsRejectedAndNothingIsFinalized() {
        var result = service.handleWebhook(CAPTURED, "bad");

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.REJECTED);
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void nonPaymentEventIsAcknowledgedButIgnored() throws Exception {
        var result = hook(CAPTURED.replace("payment.captured", "payment.failed"));

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.IGNORED);
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void capturedEventForUnknownOrderIsIgnored() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.empty());

        assertThat(hook(CAPTURED).outcome()).isEqualTo(PaymentService.WebhookOutcome.IGNORED);
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void wrongAmountOrCurrencyIsNotFinalizedButIsAcknowledged() throws Exception {
        when(orders.findByGatewayOrderId("order_abc")).thenReturn(Optional.of(awaitingOrder()));

        assertThat(hook(CAPTURED.replace("45000", "100")).outcome()).isEqualTo(PaymentService.WebhookOutcome.IGNORED);
        assertThat(hook(CAPTURED.replace("\"INR\"", "\"USD\"")).outcome()).isEqualTo(PaymentService.WebhookOutcome.IGNORED);
        verify(finalize, never()).finalizeOnline(any(), any(), anyInt());
    }

    @Test
    void onlyABadSignatureIsRejectedWith400Semantics() {
        var result = service.handleWebhook(CAPTURED, null);

        assertThat(result.outcome()).isEqualTo(PaymentService.WebhookOutcome.REJECTED);
    }
}
