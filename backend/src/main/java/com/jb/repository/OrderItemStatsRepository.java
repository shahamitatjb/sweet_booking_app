package com.jb.repository;

import com.jb.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OrderItemStatsRepository extends JpaRepository<OrderItem, Long> {
    @Query("""
        select oi.itemName, oi.packSize, sum(oi.quantity)
        from Booking b
        join b.order o
        join OrderItem oi on oi.order = o
        where b.confirmedAt >= :from
        group by oi.itemName, oi.packSize
        order by oi.itemName
        """)
    List<Object[]> packetsPerItemSince(@Param("from") Instant from);
}
