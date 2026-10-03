package com.jb.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Item {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_hi")
    private String nameHi;

    @Column(name = "name_gu")
    private String nameGu;

    @Column(name = "pack_size", nullable = false)
    private String packSize;

    @Column(name = "price_paise", nullable = false)
    private Integer pricePaise;

    @Column(name = "weight_kg", nullable = false)
    private BigDecimal weightKg;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String nameFor(String lang) {
        if ("hi".equals(lang) && nameHi != null && !nameHi.isBlank()) return nameHi;
        if ("gu".equals(lang) && nameGu != null && !nameGu.isBlank()) return nameGu;
        return nameEn;
    }
}
