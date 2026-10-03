package com.jb.repository;

import com.jb.domain.Order;
import com.jb.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemStatsRepository extends JpaRepository<OrderItem, Long> {
    // All-time per item summary: [itemId, itemName, packSize, packets, amountPaise].
    // Voided bookings are excluded so the rows add up to the dashboard totals.
    @Query("""
        select oi.itemId, oi.itemName, oi.packSize, sum(oi.quantity), sum(oi.quantity * oi.unitPrice)
        from Booking b
        join b.order o
        join OrderItem oi on oi.order = o
        where o.status <> :status
        group by oi.itemId, oi.itemName, oi.packSize
        order by oi.itemName
        """)
    List<Object[]> itemSummaryExcludingStatus(@Param("status") Order.Status status);
}
