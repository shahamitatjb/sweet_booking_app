package com.jb.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {
    public enum Status { created, awaiting_payment, paid, failed, voided }
    public enum Channel { online, counter }
    public enum PaymentMethod { gateway, cash, upi }

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.created;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String mobile;

    @Column(nullable = false)
    private String address;

    @Column(name = "pin_code", nullable = false)
    private String pinCode;

    private String email;

    @Column(name = "total_amount", nullable = false)
    private Integer totalAmount;

    @Column(name = "total_packets", nullable = false)
    private int totalPackets;

    @Column(name = "total_weight_kg", nullable = false)
    private java.math.BigDecimal totalWeightKg = java.math.BigDecimal.ZERO;

    @Column(name = "gateway_order_id")
    private String gatewayOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    @Column(name = "upi_reference")
    private String upiReference;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "accepted_terms_at")
    private Instant acceptedTermsAt;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
