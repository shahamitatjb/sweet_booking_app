package com.jb.web;

import com.jb.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/public/receipts")
@RequiredArgsConstructor
public class PublicReceiptController {
    private final ReceiptService receiptService;

    @GetMapping("/{bookingId}")
    public ResponseEntity<?> get(@PathVariable String bookingId) {
        try {
            return ResponseEntity.ok(receiptService.view(bookingId, true));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", "Receipt not available"));
        }
    }
}
