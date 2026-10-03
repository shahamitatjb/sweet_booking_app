package com.jb.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(SettingsId.class)
public class Settings {
    @Id
    @Column(name = "key", nullable = false)
    private String key;

    @Id
    @Column(nullable = false)
    private String language = "en";

    @Column(nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
