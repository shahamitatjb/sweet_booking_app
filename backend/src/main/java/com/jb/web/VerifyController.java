package com.jb.web;

import com.jb.service.VerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/verify")
@RequiredArgsConstructor
public class VerifyController {
    private final VerificationService verificationService;

    @GetMapping("/{token}")
    public Map<String, Object> verifyToken(@PathVariable String token) {
        // token format: JB-0001.signature
        int idx = token.lastIndexOf('.');
        if (idx <= 0) {
            return Map.of("status", "invalid", "message", "Invalid receipt");
        }
        String bookingId = token.substring(0, idx);
        String signature = token.substring(idx + 1);
        return verificationService.verify(bookingId, signature);
    }

    @GetMapping("/{bookingId}/staff")
    public Map<String, Object> staffDetail(@PathVariable String bookingId) {
        return verificationService.staffDetail(bookingId);
    }
}
