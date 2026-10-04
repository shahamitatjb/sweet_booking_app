package com.jb.service;

import com.jb.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RazorpayService {
    @Value("${jb.razorpay-mode:test}")
    private String mode;

    @Value("${jb.razorpay-key-id:}")
    private String keyId;

    @Value("${jb.razorpay-key-secret:}")
    private String keySecret;

    @Value("${jb.razorpay-webhook-secret:}")
    private String webhookSecret;

    public boolean enabled() {
        return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
    }

    public Map<String, Object> createOrder(Order order) {
        if (!enabled()) {
            log.error("[PAY] Razorpay keys not configured (RAZORPAY_KEY_ID/SECRET) — order {} cannot be paid online",
                    order.getId());
            throw new IllegalStateException("Razorpay keys not configured. Set RAZORPAY_KEY_ID/SECRET for online payments.");
        }
        log.info("[PAY] creating Razorpay order for orderId={} amountPaise={} currency=INR mode={}",
                order.getId(), order.getTotalAmount(), mode);
        try {
            com.razorpay.RazorpayClient client = new com.razorpay.RazorpayClient(keyId, keySecret);
            JSONObject request = new JSONObject();
            request.put("amount", order.getTotalAmount());
            request.put("currency", "INR");
            request.put("receipt", "jb_" + order.getId());
            JSONObject notes = new JSONObject();
            notes.put("order_id", order.getId().toString());
            request.put("notes", notes);
            com.razorpay.Order rzpOrder = client.orders.create(request);
            log.info("[PAY] Razorpay order created: gatewayOrderId={} for localOrderId={}",
                    rzpOrder.get("id"), order.getId());
            return Map.of(
                    "gatewayOrderId", String.valueOf(rzpOrder.get("id")),
                    "amount", order.getTotalAmount(),
                    "currency", "INR",
                    "keyId", keyId,
                    "mode", mode
            );
        } catch (Exception e) {
            log.error("[PAY] Razorpay order create FAILED for localOrderId={} amountPaise={}",
                    order.getId(), order.getTotalAmount(), e);
            throw new IllegalStateException("Razorpay order create failed: " + e.getMessage(), e);
        }
    }

    /** Checkout callback check: HMAC-SHA256 of "order_id|payment_id" with the key secret. */
    public boolean verifyPaymentSignature(String gatewayOrderId, String paymentId, String signature) {
        if (!enabled() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            JSONObject attrs = new JSONObject();
            attrs.put("razorpay_order_id", gatewayOrderId);
            attrs.put("razorpay_payment_id", paymentId);
            attrs.put("razorpay_signature", signature);
            return com.razorpay.Utils.verifyPaymentSignature(attrs, keySecret);
        } catch (Exception e) {
            log.error("[PAY] payment signature verify error for gatewayOrderId={}", gatewayOrderId, e);
            return false;
        }
    }

    /**
     * Bank-side reference of a payment, for reconciling with the bank statement. Fetched from Razorpay
     * because the checkout callback only carries the payment id. Empty when the call fails or Razorpay
     * has no reference yet; the payment.captured webhook fills it in later.
     */
    public Optional<String> fetchBankReference(String paymentId) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            com.razorpay.RazorpayClient client = new com.razorpay.RazorpayClient(keyId, keySecret);
            com.razorpay.Payment payment = client.payments.fetch(paymentId);
            Optional<String> ref = bankReference(payment.toJson());
            log.info("[PAY] bank reference fetched for paymentId={}: {}", paymentId, ref.orElse("(none yet)"));
            return ref;
        } catch (Exception e) {
            log.warn("[PAY] bank reference fetch FAILED for paymentId={} — webhook will fill it in: {}",
                    paymentId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Reads the bank reference from a Razorpay payment entity: the RRN/UTR for UPI (and most cards),
     * the bank transaction id for netbanking, the wallet transaction id, or else the card auth code.
     */
    public static Optional<String> bankReference(JSONObject paymentEntity) {
        if (paymentEntity == null) {
            return Optional.empty();
        }
        JSONObject acquirer = paymentEntity.optJSONObject("acquirer_data");
        if (acquirer == null) {
            return Optional.empty();
        }
        for (String key : new String[] {"rrn", "bank_transaction_id", "transaction_id", "auth_code"}) {
            String v = acquirer.isNull(key) ? "" : acquirer.optString(key, "").trim();
            if (!v.isEmpty()) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public boolean verifyWebhookSignature(String body, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("[PAY] Webhook secret missing — rejecting webhook (bodyBytes={})",
                    body == null ? 0 : body.length());
            return false;
        }
        try {
            boolean ok = com.razorpay.Utils.verifyWebhookSignature(body, signatureHeader, webhookSecret);
            if (!ok) {
                log.warn("[PAY] Webhook signature INVALID — rejected (bodyBytes={}, signaturePresent={})",
                        body == null ? 0 : body.length(), signatureHeader != null);
            }
            return ok;
        } catch (Exception e) {
            log.error("[PAY] Webhook signature verify error", e);
            return false;
        }
    }
}
