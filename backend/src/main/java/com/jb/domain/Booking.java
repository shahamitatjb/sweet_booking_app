package com.jb.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_no", nullable = false, unique = true)
    private Long bookingNo;

    @Column(name = "booking_id", nullable = false, unique = true)
    private String bookingId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", unique = true)
    private Order order;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @Column(name = "qr_signature", nullable = false)
    private String qrSignature;

    @Column(name = "qr_key_id", nullable = false)
    private String qrKeyId = "k1";

    @Column(name = "first_scanned_at")
    private Instant firstScannedAt;

    @Column(name = "scan_count", nullable = false)
    private int scanCount;

    /** Set when a treasurer has matched the payment against Razorpay / bank statements. */
    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "reconciled_by")
    private Long reconciledBy;

    @Column(name = "reconcile_note")
    private String reconcileNote;
}
