package com.jb.jobs;

import com.jb.domain.Order;
import com.jb.repository.OrderRepository;
import com.jb.service.RazorpayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Re-check orders stuck in awaiting_payment.
 * Full auto-refund path requires live Razorpay keys + payment lookup; scaffold logs mismatches.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StuckPaymentJob {
    private final OrderRepository orderRepository;
    private final RazorpayService razorpayService;

    @Scheduled(fixedDelayString = "300000")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(30));
        List<Order> stuck = orderRepository.findStuckAwaitingPayment(cutoff);
        for (Order o : stuck) {
            log.warn("Stuck awaiting_payment order {} amount={} gatewayOrder={}",
                    o.getId(), o.getTotalAmount(), o.getGatewayOrderId());
            if (!razorpayService.enabled()) {
                continue;
            }
            // Production: fetch payment by gateway order id; if captured and no booking → refund
        }
    }
}
