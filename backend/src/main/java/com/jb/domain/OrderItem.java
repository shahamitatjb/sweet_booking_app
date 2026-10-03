package com.jb.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "pack_size", nullable = false)
    private String packSize;

    @Column(name = "unit_price", nullable = false)
    private Integer unitPrice;

    @Column(name = "weight_kg", nullable = false)
    private BigDecimal weightKg = BigDecimal.ZERO;

    @Column(nullable = false)
    private int quantity;
}
