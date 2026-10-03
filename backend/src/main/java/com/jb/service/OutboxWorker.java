package com.jb.service;

import com.jb.domain.NotificationOutbox;
import com.jb.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxWorker {
    private final NotificationOutboxRepository outboxRepository;
    private final EmailService emailService;
    private final ReceiptService receiptService;

    @Scheduled(fixedDelayString = "15000")
    @Transactional
    public void processPending() {
        List<NotificationOutbox> due = outboxRepository.findDue(Instant.now());
        if (due.isEmpty()) {
            log.debug("[NOTIFY] outbox: nothing due");
            return;
        }
        log.info("[NOTIFY] outbox: {} message(s) due", due.size());
        for (NotificationOutbox row : due) {
            try {
                if ("receipt_pdf".equals(row.getKind())) {
                    byte[] pdf = receiptService.pdfForBookingId(row.getBookingId());
                    String body = "Thank you for your booking. Receipt " + row.getBookingId() + ".";
                    emailService.sendWithPdf(row.getToAddress(), "Your Diwali booking " + row.getBookingId(), body, pdf);
                } else {
                    emailService.sendSimple(row.getToAddress(),
                            "New booking " + row.getBookingId(),
                            "Booking " + row.getBookingId() + " has been confirmed. See admin dashboard for details.");
                }
                row.setStatus(NotificationOutbox.Status.sent);
                row.setAttempts(row.getAttempts() + 1);
                log.info("[NOTIFY] sent {} for booking {} to {} (attempt {})",
                        row.getKind(), row.getBookingId(), row.getToAddress(), row.getAttempts());
            } catch (Exception e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(e.getMessage());
                long backoffMin = Math.min(60, (long) Math.pow(2, row.getAttempts()));
                row.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(backoffMin)));
                if (row.getAttempts() >= 8) {
                    row.setStatus(NotificationOutbox.Status.failed);
                    log.error("[NOTIFY] {} for booking {} to {} FAILED permanently after {} attempts; giving up. Last error: {}",
                            row.getKind(), row.getBookingId(), row.getToAddress(), row.getAttempts(), e.getMessage(), e);
                } else {
                    row.setStatus(NotificationOutbox.Status.pending);
                    log.warn("[NOTIFY] {} for booking {} to {} failed (attempt {}), retrying in {} min: {}",
                            row.getKind(), row.getBookingId(), row.getToAddress(), row.getAttempts(),
                            backoffMin, e.toString());
                }
            }
            outboxRepository.save(row);
        }
    }
}
