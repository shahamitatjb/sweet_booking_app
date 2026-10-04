package com.jb.repository;

import com.jb.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByGatewayOrderId(String gatewayOrderId);

    @Query("select o from Order o where o.status = 'awaiting_payment' and o.createdAt < :cutoff")
    List<Order> findStuckAwaitingPayment(@Param("cutoff") Instant cutoff);

    /** Online orders with a Razorpay order that are still unpaid, created inside [from, to). */
    @Query("select o from Order o where o.status = 'awaiting_payment' and o.gatewayOrderId is not null"
            + " and o.createdAt >= :from and o.createdAt < :to order by o.createdAt")
    List<Order> findAwaitingGatewayPayment(@Param("from") Instant from, @Param("to") Instant to);

    /** Marks an abandoned checkout failed only if nothing confirmed or cancelled it meanwhile. */
    @Modifying
    @Transactional
    @Query("update Order o set o.status = 'failed', o.updatedAt = :now where o.id = :id and o.status = 'awaiting_payment'")
    int markFailedIfStillAwaiting(@Param("id") java.util.UUID id, @Param("now") Instant now);

    List<Order> findByStatusAndCreatedAtBetween(Order.Status status, Instant from, Instant to);
}
