package com.security.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entidad para direcciones de usuarios (envío y facturación)
 * Tabla: addresses
 */
@Entity
@Table(name = "addresses", schema = "customer", indexes = {
        @Index(name = "idx_addresses_user", columnList = "user_id, active"),
        @Index(name = "idx_addresses_default", columnList = "user_id, is_default")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "address_type", length = 20, nullable = false)
    private AddressType addressType = AddressType.BOTH;

    @Column(name = "street", length = 200, nullable = false)
    private String street; // Calle y número

    @Column(name = "apartment", length = 50)
    private String apartment; // Departamento, piso, oficina

    @Column(name = "neighborhood", length = 100)
    private String neighborhood; // Colonia o barrio

    @Column(name = "city", length = 100, nullable = false)
    private String city;

    @Column(name = "state", length = 100, nullable = false)
    private String state; // Estado o provincia

    @Column(name = "postal_code", length = 20, nullable = false)
    private String postalCode;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "recipient_name", length = 200)
    private String recipientName; // Nombre de quien recibe

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone; // Teléfono de contacto

    @Column(name = "reference", length = 300)
    private String reference; // Referencias para encontrar la dirección

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.active == null) {
            this.active = true;
        }
        if (this.isDefault == null) {
            this.isDefault = false;
        }
        if (this.addressType == null) {
            this.addressType = AddressType.BOTH;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Enum para tipos de dirección
     */
    public enum AddressType {
        BILLING, // Facturación
        SHIPPING, // Envío
        BOTH // Ambas
    }
}
