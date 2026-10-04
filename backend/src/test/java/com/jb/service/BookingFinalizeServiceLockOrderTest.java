package com.jb.service;

import com.jb.domain.Booking;
import com.jb.domain.Counter;
import com.jb.domain.Order;
import com.jb.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Webhook and browser callback can finalise the same order at the same time. The counter row
 * lock must be taken BEFORE the "already paid?" check, otherwise the second transaction reads
 * stale state, proceeds, and dies on the bookings.order_id unique constraint (a 500 for a paid customer).
 */
class BookingFinalizeServiceLockOrderTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final CounterRepository counters = mock(CounterRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final BookingFinalizeService service = new BookingFinalizeService(
            orders, counters, bookings, mock(PaymentRepository.class), mock(AuditService.class),
            mock(NotificationOutboxRepository.class), mock(SettingsService.class),
            mock(QrService.class));

    @Test
    void counterLockIsAcquiredBeforeTheIdempotencyCheckAndExistingBookingIsReturned() {
        UUID orderId = UUID.randomUUID();
        Order paid = Order.builder().id(orderId).status(Order.Status.paid).totalAmount(100).build();
        Booking existing = Booking.builder().bookingId("JB-0042").order(paid).build();
        Counter counter = new Counter();
        counter.setName("booking");
        counter.setValue(42);
        when(counters.findForUpdate("booking")).thenReturn(Optional.of(counter));
        when(orders.findById(orderId)).thenReturn(Optional.of(paid));
        when(bookings.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        Booking result = service.finalizeOnline(orderId, "pay_1", 100);

        assertThat(result.getBookingId()).isEqualTo("JB-0042");
        InOrder inOrder = inOrder(counters, orders, bookings);
        inOrder.verify(counters).findForUpdate("booking");
        inOrder.verify(orders).findById(orderId);
        inOrder.verify(bookings).findByOrderId(orderId);
        verify(counters, never()).save(any());
        assertThat(counter.getValue()).isEqualTo(42);
    }
}
