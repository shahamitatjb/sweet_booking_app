package com.jb.web;

import com.jb.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/public/receipts")
@RequiredArgsConstructor
public class PublicReceiptController {
    private final ReceiptService receiptService;

    @GetMapping(value = "/{bookingId}/qr.png", produces = "image/png")
    public ResponseEntity<byte[]> qr(@PathVariable String bookingId) {
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "public, max-age=86400")
                    .body(receiptService.qrPng(bookingId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<?> get(@PathVariable String bookingId) {
        try {
            return ResponseEntity.ok(receiptService.view(bookingId, true));
        } catch (Exception e) {
            log.warn("[BOOKING] public receipt lookup failed for {}: {}", bookingId, e.toString());
            return ResponseEntity.status(404).body(Map.of("error", "Receipt not available"));
        }
    }
}
