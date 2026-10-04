package com.jb.web;

import com.jb.domain.Booking;
import com.jb.service.PaymentService;
import com.jb.service.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicPaymentControllerTest {
    private static final UUID ORDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final PaymentService payments = mock(PaymentService.class);
    private final ReceiptService receipts = mock(ReceiptService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PublicPaymentController(payments, receipts)).build();

    private static String body() {
        return """
                {"orderId":"%s","razorpayOrderId":"order_abc","razorpayPaymentId":"pay_123","razorpaySignature":"sig"}
                """.formatted(ORDER_ID);
    }

    @Test
    void verifiedPaymentReturnsBookingIdAndPrivateReceiptUrl() throws Exception {
        when(payments.verifyAndFinalize(ORDER_ID, "order_abc", "pay_123", "sig"))
                .thenReturn(Booking.builder().bookingId("JB-0007").qrSignature("secretTok").build());
        when(receipts.receiptPath(any())).thenCallRealMethod();

        mvc.perform(post("/api/public/payments/verify").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("JB-0007"))
                .andExpect(jsonPath("$.receiptUrl").value("/receipt/JB-0007?t=secretTok"));
    }

    @Test
    void rejectedPaymentIs400WithAGenericMessageNotTheInternalReason() throws Exception {
        when(payments.verifyAndFinalize(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Amount mismatch for order " + ORDER_ID));

        mvc.perform(post("/api/public/payments/verify").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(PublicPaymentController.GENERIC_FAILURE));
    }

    @Test
    void missingFieldsAre400WithoutCallingTheService() throws Exception {
        mvc.perform(post("/api/public/payments/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(payments);
    }
}
