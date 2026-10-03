package com.jb.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "counters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Counter {
    @Id
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long value;
}
