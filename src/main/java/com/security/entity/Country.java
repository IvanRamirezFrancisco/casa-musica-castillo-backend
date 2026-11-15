package com.security.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entidad para catálogo de países (ISO 3166-1 alpha-2)
 * Tabla: countries
 */
@Entity
@Table(name = "countries", schema = "security")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Country {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", length = 2, nullable = false, unique = true)
    private String code; // Código ISO 3166-1 alpha-2 (ej: MX, US, ES)

    @Column(name = "name", length = 100, nullable = false)
    private String name; // Nombre del país (ej: México, Estados Unidos)

    @Column(name = "phone_prefix", length = 5)
    private String phonePrefix; // Prefijo telefónico (ej: +52, +1)

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.active == null) {
            this.active = true;
        }
    }
}
