package com.jb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {
    @Value("${jb.email-provider}")
    private String provider;

    @Value("${jb.email-from}")
    private String from;

    @Value("${jb.resend-api-key}")
    private String resendApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public void sendSimple(String to, String subject, String body) {
        String p = provider == null ? "console" : provider.trim().toLowerCase();
        if ("console".equals(p)) {
            log.info("[EMAIL:console] to={} subject={} body={}", to, subject, body);
            return;
        }
        if ("resend".equals(p)) {
            sendResend(to, subject, body);
            return;
        }
        log.warn("Unknown email provider {}, logging only", p);
        log.info("[EMAIL:{}] to={} subject={}", p, to, subject);
    }

    public void sendWithPdf(String to, String subject, String body, byte[] pdf) {
        sendSimple(to, subject, body + "\n\nReceipt PDF will be attached in production email.");
        if (pdf != null && pdf.length > 0) {
            log.info("PDF generated ({} bytes) for {}", pdf.length, to);
        }
    }

    private void sendResend(String to, String subject, String body) {
        if (resendApiKey == null || resendApiKey.isBlank()) {
            log.warn("RESEND_API_KEY missing; logging email instead of sending");
            log.info("[EMAIL:resend-skipped] to={} subject={}", to, subject);
            return;
        }
        try {
            String json = "{\"from\":\"" + escape(from) + "\",\"to\":[\"" + escape(to) +
                    "\"],\"subject\":\"" + escape(subject) + "\",\"text\":\"" + escape(body) + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Resend HTTP " + response.statusCode() + ": " + response.body());
            }
            log.info("Email sent via Resend to {}", to);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Resend send interrupted", ie);
        } catch (Exception e) {
            throw new IllegalStateException("Resend send failed: " + e.getMessage(), e);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
