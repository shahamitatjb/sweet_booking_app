package com.jb.service;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.util.Optional;

/** The few fields of a Razorpay webhook body that booking finalisation needs. */
@Slf4j
public final class RazorpayWebhookPayload {
    public static final String EVENT_PAYMENT_CAPTURED = "payment.captured";

    private RazorpayWebhookPayload() {}

    public record CapturedPayment(String paymentId, String gatewayOrderId, int amountPaise) {}

    /** Empty for any event other than payment.captured, and for bodies that cannot be read. */
    public static Optional<CapturedPayment> parseCaptured(String body) {
        try {
            JSONObject root = new JSONObject(body);
            if (!EVENT_PAYMENT_CAPTURED.equals(root.optString("event"))) {
                return Optional.empty();
            }
            JSONObject entity = root.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
            return Optional.of(new CapturedPayment(
                    entity.getString("id"), entity.getString("order_id"), entity.getInt("amount")));
        } catch (RuntimeException e) {
            log.warn("[PAY] webhook body not understood: {}", e.toString());
            return Optional.empty();
        }
    }
}
