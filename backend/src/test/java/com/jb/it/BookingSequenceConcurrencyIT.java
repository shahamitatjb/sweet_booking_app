package com.jb.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.jb.service.BookingFinalizeService;
import com.jb.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec's core promise: booking IDs are gapless and unique, even when confirmations race.
 * Real threads, real row locks, real Postgres.
 */
class BookingSequenceConcurrencyIT extends IntegrationTestBase {
    private static final int THREADS = 8;

    @Autowired PaymentService paymentService;
    @Autowired BookingFinalizeService finalizeService;

    private <T> List<T> raceAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyOrdersConfirmedAtOnceGetDistinctGaplessIds() throws Exception {
        List<UUID> orderIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            orderIds.add(UUID.fromString(createOnlineOrder(ladoo.getId(), 1).get("orderId").asText()));
        }

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UUID id = orderIds.get(i);
            String paymentId = "pay_RACE" + i;
            tasks.add(() -> finalizeService.finalizeOnline(id, paymentId, 25000).getBookingId());
        }
        List<String> ids = raceAll(tasks);

        Set<String> expected = ConcurrentHashMap.newKeySet();
        for (int i = 1; i <= THREADS; i++) expected.add(String.format("JB-%04d", i));
        assertThat(ids).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(bookingCounter()).isEqualTo(THREADS);
        assertThat(count("bookings")).isEqualTo(THREADS);
    }

    @Test
    void checkoutCallbackAndWebhookRacingForOneOrderMakeOneBooking() throws Exception {
        JsonNode order = createOnlineOrder(barfi.getId(), 1);
        UUID orderId = UUID.fromString(order.get("orderId").asText());
        String gatewayOrderId = order.at("/gateway/gatewayOrderId").asText();
        String signature = hmacHex(RZP_KEY_SECRET, gatewayOrderId + "|pay_ONE");
        String hook = capturedWebhook(gatewayOrderId, "pay_ONE", 30000);
        String hookSig = hmacHex(RZP_WEBHOOK_SECRET, hook);

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            if (i % 2 == 0) {
                tasks.add(() -> paymentService.verifyAndFinalize(orderId, gatewayOrderId, "pay_ONE", signature).getBookingId());
            } else {
                tasks.add(() -> paymentService.handleWebhook(hook, hookSig).bookingId());
            }
        }
        List<String> ids = raceAll(tasks);

        assertThat(ids).containsOnly("JB-0001");
        assertThat(count("bookings")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(bookingCounter()).isEqualTo(1);
    }

    @Test
    void aFailedConfirmationDoesNotBurnANumber() throws Exception {
        UUID bad = UUID.fromString(createOnlineOrder(ladoo.getId(), 1).get("orderId").asText());
        UUID good = UUID.fromString(createOnlineOrder(ladoo.getId(), 1).get("orderId").asText());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> finalizeService.finalizeOnline(bad, "pay_X", 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(finalizeService.finalizeOnline(good, "pay_Y", 25000).getBookingId()).isEqualTo("JB-0001");
    }
}
