package com.jb.service;

import com.jb.domain.NotificationOutbox;
import com.jb.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWorkerReceiptLinkTest {
    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final EmailService email = mock(EmailService.class);
    private final ReceiptService receipts = mock(ReceiptService.class);
    private final OutboxWorker worker = new OutboxWorker(outbox, email, receipts);

    @Test
    void receiptEmailCarriesTheCustomersPrivateLink() {
        NotificationOutbox row = NotificationOutbox.builder()
                .bookingId("JB-0007").kind("receipt_pdf").toAddress("ravi@example.com")
                .template("receipt_pdf").status(NotificationOutbox.Status.pending).build();
        when(outbox.findDue(any())).thenReturn(List.of(row));
        when(receipts.pdfForBookingId("JB-0007")).thenReturn(new byte[] {1});
        when(receipts.receiptUrl("JB-0007")).thenReturn("https://jb.example/receipt/JB-0007?t=secretTok");

        worker.processPending();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendWithPdf(eq("ravi@example.com"), any(), body.capture(), any());
        assertThat(body.getValue()).contains("https://jb.example/receipt/JB-0007?t=secretTok");
        assertThat(row.getStatus()).isEqualTo(NotificationOutbox.Status.sent);
    }
}
