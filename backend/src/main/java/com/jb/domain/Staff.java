package com.jb.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "staff")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Staff {
    /**
     * SUPER_ADMIN: everything, including Settings. ADMIN and TREASURER: dashboard, bookings,
     * export, void and counter; TREASURER also reconciles payments. COUNTER: counter bookings.
     * Declared in display order (the staff list sorts by it).
     */
    public enum Role {
        SUPER_ADMIN, ADMIN, TREASURER, COUNTER;

        /** Roles that see the dashboard and bookings. */
        public boolean isDashboardRole() {
            return this != COUNTER;
        }

        /** Roles that may mark bookings reconciled against Razorpay and bank statements. */
        public boolean canReconcile() {
            return this == SUPER_ADMIN || this == TREASURER;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
