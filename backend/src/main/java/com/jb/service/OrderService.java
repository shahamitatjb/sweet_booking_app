package com.jb.service;

import com.jb.domain.Order;
import com.jb.domain.OrderItem;
import com.jb.repository.ItemRepository;
import com.jb.repository.OrderItemRepository;
import com.jb.repository.OrderRepository;
import com.jb.repository.SettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    private final ItemRepository itemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SettingsService settingsService;

    public record CartLine(Long itemId, int quantity) {}
    public record Customer(String name, String mobile, String address, String pinCode, String email) {}

    @Transactional
    public Order createOnlineOrder(List<CartLine> lines, Customer customer, boolean acceptedTerms) {
        return create(lines, customer, Order.Channel.online, null, acceptedTerms);
    }

    @Transactional
    public Order createCounterOrder(List<CartLine> lines, Customer customer, Order.PaymentMethod method, Long staffId) {
        return create(lines, customer, Order.Channel.counter, method, true);
    }

    private Order create(List<CartLine> lines, Customer customer, Order.Channel channel,
                         Order.PaymentMethod method, boolean acceptedTerms) {
        log.info("[BOOKING] {} order start: mobile={} pin={} lines={} method={}",
                channel, customer == null ? null : customer.mobile(),
                customer == null ? null : customer.pinCode(),
                lines == null ? 0 : lines.size(), method);
        if (!settingsService.withinWindow(Instant.now()) && channel == Order.Channel.online) {
            throw reject(channel, "Booking window is closed");
        }
        validateCustomer(customer);
        validatePins(customer.pinCode());

        if (lines == null || lines.isEmpty()) {
            throw reject(channel, "At least one packet required");
        }

        int maxPer = settingsService.maxPacketsPerItem();
        int maxTotal = settingsService.maxPacketsTotal();
        int totalPackets = 0;
        BigDecimal totalWeight = BigDecimal.ZERO;
        int totalAmount = 0;
        List<OrderItem> items = new ArrayList<>();

        Order order = Order.builder()
                .status(Order.Status.created)
                .channel(channel)
                .customerName(customer.name())
                .mobile(customer.mobile())
                .address(customer.address())
                .pinCode(customer.pinCode())
                .email(customer.email())
                .paymentMethod(method)
                .acceptedTermsAt(acceptedTerms ? Instant.now() : null)
                .totalAmount(0)
                .totalPackets(0)
                .totalWeightKg(BigDecimal.ZERO)
                .build();

        for (CartLine line : lines) {
            if (line.quantity() <= 0) {
                log.info("[BOOKING] {} order: skipping non-positive quantity for item {}", channel, line.itemId());
                continue;
            }
            if (line.quantity() > maxPer) {
                throw reject(channel, "Quantity exceeds max per item (item=" + line.itemId()
                        + ", qty=" + line.quantity() + ", max=" + maxPer + ")");
            }
            var item = itemRepository.findById(line.itemId())
                    .orElseThrow(() -> reject(channel, "Item not found: " + line.itemId()));
            if (!item.isActive()) {
                throw reject(channel, "Item inactive: " + item.getNameEn());
            }
            totalPackets += line.quantity();
            totalAmount += item.getPricePaise() * line.quantity();
            totalWeight = totalWeight.add(item.getWeightKg().multiply(BigDecimal.valueOf(line.quantity())));
            items.add(OrderItem.builder()
                    .order(order)
                    .itemId(item.getId())
                    .itemName(item.getNameEn())
                    .packSize(item.getPackSize())
                    .unitPrice(item.getPricePaise())
                    .weightKg(item.getWeightKg())
                    .quantity(line.quantity())
                    .build());
        }
        if (totalPackets <= 0) {
            throw reject(channel, "At least one packet required");
        }
        if (totalPackets > maxTotal) {
            throw reject(channel, "Total packets exceed limit (packets=" + totalPackets + ", max=" + maxTotal + ")");
        }

        order.setTotalPackets(totalPackets);
        order.setTotalAmount(totalAmount);
        order.setTotalWeightKg(totalWeight);
        orderRepository.save(order);
        orderItemRepository.saveAll(items);

        if (channel == Order.Channel.online) {
            order.setStatus(Order.Status.awaiting_payment);
            orderRepository.save(order);
        } else {
            order.setStatus(Order.Status.created);
            orderRepository.save(order);
        }
        log.info("[BOOKING] {} order created: orderId={} packets={} amountPaise={} weightKg={} lines={} status={}",
                channel, order.getId(), totalPackets, totalAmount, totalWeight, items.size(), order.getStatus());
        return order;
    }

    /** Logs the rejection (exit point) before throwing. */
    private IllegalArgumentException reject(Order.Channel channel, String message) {
        log.warn("[BOOKING] {} order rejected: {}", channel, message);
        return new IllegalArgumentException(message);
    }

    public void validateCustomer(Customer c) {
        if (c.name() == null || c.name().trim().length() < 3) {
            throw new IllegalArgumentException("Name must be at least 3 characters");
        }
        if (c.mobile() == null || !c.mobile().matches("[6-9]\\d{9}")) {
            throw new IllegalArgumentException("Mobile must be 10 digits starting 6-9");
        }
        if (c.address() == null || c.address().trim().length() < 10) {
            throw new IllegalArgumentException("Address must be at least 10 characters");
        }
        if (c.pinCode() == null || !c.pinCode().matches("\\d{6}")) {
            throw new IllegalArgumentException("Pin code must be 6 digits");
        }
        if (c.email() != null && !c.email().isBlank() && !c.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Invalid email");
        }
    }

    public void validatePins(String pin) {
        if (!settingsService.isPinAllowed(pin)) {
            log.warn("[BOOKING] pin code rejected (not in allowed Pune/PCMC list): {}", pin);
            throw new IllegalArgumentException("Pin code not in allowed Pune/PCMC list");
        }
    }

    public List<OrderItem> itemsOf(UUID orderId) {
        return orderItemRepository.findByOrderId(orderId);
    }
}
