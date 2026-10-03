package com.jb.repository;

import com.jb.domain.Booking;
import com.jb.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    // Order is LAZY and most callers read it after the repository transaction has
    // closed, so fetch it here instead of letting them hit LazyInitializationException.
    @Query("select b from Booking b left join fetch b.order where b.bookingId = :bookingId")
    Optional<Booking> findByBookingId(@Param("bookingId") String bookingId);
    Optional<Booking> findByOrderId(UUID orderId);
    Optional<Booking> findByBookingNo(Long bookingNo);

    @Query("select b from Booking b left join fetch b.order order by b.bookingNo desc")
    List<Booking> findAllOrderByBookingNoDesc();

    // Voided bookings are kept as history but must not appear in operational lists.
    @Query("select b from Booking b left join fetch b.order o where o.status <> :status order by b.bookingNo desc")
    List<Booking> findAllExcludingStatus(@Param("status") Order.Status status);

    @Query("select b from Booking b left join fetch b.order o where o.status = :status order by b.bookingNo desc")
    List<Booking> findAllWithStatus(@Param("status") Order.Status status);

    @Query("select coalesce(sum(oi.quantity), 0) from Booking b join b.order o join OrderItem oi on oi.order = o where b.confirmedAt >= :from")
    long sumPacketsSince(Instant from);
}
