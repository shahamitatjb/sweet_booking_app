package com.jb.web;

import com.jb.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {
    private final PaymentService paymentService;

    @PostMapping("/razorpay")
    public ResponseEntity<?> razorpay(@RequestBody String body,
                                      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        log.info("[PAY] Razorpay webhook received: bodyBytes={} signaturePresent={}",
                body == null ? 0 : body.length(), signature != null);
        PaymentService.WebhookResult result = paymentService.handleWebhook(body, signature);
        if (result.outcome() == PaymentService.WebhookOutcome.REJECTED) {
            log.warn("[PAY] Razorpay webhook REJECTED ({}) — 400 returned", result.error());
            return ResponseEntity.status(400).body(Map.of("error", result.error()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", true);
        if (result.bookingId() != null) {
            out.put("bookingId", result.bookingId());
        }
        return ResponseEntity.ok(out);
    }
}
