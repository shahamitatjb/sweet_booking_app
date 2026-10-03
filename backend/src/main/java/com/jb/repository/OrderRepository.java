package com.jb.repository;

import com.jb.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByGatewayOrderId(String gatewayOrderId);

    @Query("select o from Order o where o.status = 'awaiting_payment' and o.createdAt < :cutoff")
    List<Order> findStuckAwaitingPayment(@Param("cutoff") Instant cutoff);

    List<Order> findByStatusAndCreatedAtBetween(Order.Status status, Instant from, Instant to);
}
