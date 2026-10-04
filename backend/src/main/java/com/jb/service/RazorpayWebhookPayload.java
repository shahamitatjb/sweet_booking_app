package com.jb.service;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.util.Optional;

/** The few fields of a Razorpay webhook body that booking finalisation needs. */
@Slf4j
public final class RazorpayWebhookPayload {
    public static final String EVENT_PAYMENT_CAPTURED = "payment.captured";

    private RazorpayWebhookPayload() {}

    /** {@code bankReference} is null when Razorpay sent no acquirer reference. */
    public record CapturedPayment(String paymentId, String gatewayOrderId, int amountPaise, String bankReference) {}

    /** Empty for any event other than payment.captured, and for bodies that cannot be read. */
    public static Optional<CapturedPayment> parseCaptured(String body) {
        try {
            JSONObject root = new JSONObject(body);
            if (!EVENT_PAYMENT_CAPTURED.equals(root.optString("event"))) {
                return Optional.empty();
            }
            JSONObject entity = root.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
            return Optional.of(new CapturedPayment(
                    entity.getString("id"), entity.getString("order_id"), entity.getInt("amount"),
                    RazorpayService.bankReference(entity).orElse(null)));
        } catch (RuntimeException e) {
            log.warn("[PAY] webhook body not understood: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Any signed webhook: its event name, the payment it concerns (may carry only an id when the
     * body has no payment entity) and, for refunds and disputes, that entity's id.
     */
    public record Event(String type, RazorpayService.GatewayPayment payment, String subjectId) {}

    /** Empty for bodies that cannot be read or that name no payment. */
    public static Optional<Event> parse(String body) {
        try {
            JSONObject root = new JSONObject(body);
            String type = root.optString("event", "");
            JSONObject payload = root.optJSONObject("payload");
            if (payload == null) return Optional.empty();
            JSONObject payment = entity(payload, "payment");
            JSONObject subject = entity(payload, "refund");
            if (subject == null) subject = entity(payload, "dispute");
            if (payment == null && subject != null && !subject.optString("payment_id", "").isBlank()) {
                payment = new JSONObject().put("id", subject.getString("payment_id"));
            }
            if (payment == null || payment.optString("id", "").isBlank()) return Optional.empty();
            return Optional.of(new Event(type, RazorpayService.GatewayPayment.from(payment),
                    subject == null ? null : subject.optString("id", null)));
        } catch (RuntimeException e) {
            log.warn("[PAY] webhook body not understood: {}", e.toString());
            return Optional.empty();
        }
    }

    private static JSONObject entity(JSONObject payload, String name) {
        JSONObject wrapper = payload.optJSONObject(name);
        return wrapper == null ? null : wrapper.optJSONObject("entity");
    }
}
