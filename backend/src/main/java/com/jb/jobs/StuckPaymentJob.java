package com.jb.jobs;

import com.jb.domain.Order;
import com.jb.repository.OrderRepository;
import com.jb.service.GatewayUnavailableException;
import com.jb.service.PaymentService;
import com.jb.service.RazorpayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Catches customers who paid but whose booking was never confirmed: the browser closed before
 * the checkout callback, Razorpay was unreachable, or the webhook did not arrive. Every few
 * minutes it asks Razorpay about recent orders still awaiting payment and confirms those with a
 * captured (or, once captured, authorized) payment. Confirmation goes through the same checks as
 * the checkout callback in {@link PaymentService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StuckPaymentJob {
    /** Give the checkout callback and webhook time to confirm first. */
    static final Duration GRACE = Duration.ofMinutes(3);
    /** Unpaid this long with nothing to confirm: the checkout was abandoned; stop asking Razorpay. */
    static final Duration ABANDONED_AFTER = Duration.ofHours(2);
    /** Safety bound on the query; abandoned orders are marked failed well before this. */
    static final Duration LOOKBACK = Duration.ofDays(5);

    private final OrderRepository orderRepository;
    private final RazorpayService razorpayService;
    private final PaymentService paymentService;

    @Scheduled(fixedDelayString = "${jb.reconcile-interval-ms:180000}", initialDelayString = "${jb.reconcile-initial-delay-ms:60000}")
    public void sweep() {
        if (!razorpayService.enabled()) {
            log.debug("[PAY] reconcile skipped: Razorpay keys not set");
            return;
        }
        Instant now = Instant.now();
        List<Order> waiting = orderRepository.findAwaitingGatewayPayment(now.minus(LOOKBACK), now.minus(GRACE));
        if (waiting.isEmpty()) {
            log.debug("[PAY] reconcile: no unpaid orders to check");
            return;
        }
        int confirmed = 0;
        for (Order order : waiting) {
            try {
                if (paymentService.reconcile(order).isPresent()) {
                    confirmed++;
                } else if (order.getCreatedAt().isBefore(now.minus(ABANDONED_AFTER))) {
                    // A late payment.captured webhook can still confirm a failed order.
                    if (orderRepository.markFailedIfStillAwaiting(order.getId(), now) == 1) {
                        log.info("[PAY] reconcile: orderId={} unpaid for {}h, marked failed",
                                order.getId(), ABANDONED_AFTER.toHours());
                    }
                }
            } catch (GatewayUnavailableException e) {
                log.warn("[PAY] reconcile stopped: Razorpay unreachable ({}); next sweep retries", e.getMessage());
                break;
            } catch (RuntimeException e) {
                log.error("[PAY] reconcile failed for orderId={}", order.getId(), e);
            }
        }
        log.info("[PAY] reconcile sweep: checked={} confirmed={}", waiting.size(), confirmed);
    }
}
