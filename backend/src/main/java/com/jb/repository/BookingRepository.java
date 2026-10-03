package com.jb.repository;

import com.jb.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByBookingId(String bookingId);
    Optional<Booking> findByOrderId(UUID orderId);
    Optional<Booking> findByBookingNo(Long bookingNo);

    @Query("select b from Booking b order by b.bookingNo desc")
    List<Booking> findAllOrderByBookingNoDesc();

    @Query("select coalesce(sum(oi.quantity), 0) from Booking b join b.order o join OrderItem oi on oi.order = o where b.confirmedAt >= :from")
    long sumPacketsSince(Instant from);
}
