package com.jb.service;

import com.jb.domain.Order;
import com.jb.repository.ItemRepository;
import com.jb.repository.OrderItemRepository;
import com.jb.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderServiceAwaitingPaymentTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderService service = new OrderService(
            mock(ItemRepository.class), orders, mock(OrderItemRepository.class), mock(SettingsService.class));

    @Test
    void markAwaitingPaymentStoresGatewayOrderIdAndStatus() {
        Order order = Order.builder().id(UUID.randomUUID()).status(Order.Status.created).totalAmount(100).build();
        when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order saved = service.markAwaitingPayment(order, "order_abc");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orders).save(captor.capture());
        assertThat(captor.getValue().getGatewayOrderId()).isEqualTo("order_abc");
        assertThat(captor.getValue().getStatus()).isEqualTo(Order.Status.awaiting_payment);
        assertThat(saved.getGatewayOrderId()).isEqualTo("order_abc");
    }
}
