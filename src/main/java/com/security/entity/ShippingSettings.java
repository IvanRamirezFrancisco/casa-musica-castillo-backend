package com.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shipping_settings", schema = "sales")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShippingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pickup_enabled")
    private Boolean pickupEnabled = true;

    @Column(name = "pickup_label")
    private String pickupLabel = "Recoger en tienda";

    @Column(name = "pickup_address", columnDefinition = "TEXT")
    private String pickupAddress;

    @Column(name = "pickup_instructions", columnDefinition = "TEXT")
    private String pickupInstructions;

    @Column(name = "pickup_hours")
    private String pickupHours;

    @Column(name = "local_delivery_enabled")
    private Boolean localDeliveryEnabled = true;

    @Column(name = "local_delivery_cost", precision = 12, scale = 2)
    private BigDecimal localDeliveryCost = new BigDecimal("50.00");

    @Column(name = "local_delivery_state")
    private String localDeliveryState = "Hidalgo";

    @Column(name = "local_delivery_city")
    private String localDeliveryCity = "Huejutla de Reyes";

    @Column(name = "local_delivery_postal_codes", columnDefinition = "TEXT")
    private String localDeliveryPostalCodes;

    @Column(name = "external_shipping_enabled")
    private Boolean externalShippingEnabled = true;

    @Column(name = "external_shipping_mode")
    private String externalShippingMode = "QUOTE_REQUIRED";

    @Column(name = "external_shipping_fixed_cost", precision = 12, scale = 2)
    private BigDecimal externalShippingFixedCost;

    @Column(name = "contact_whatsapp", length = 20)
    private String contactWhatsapp;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "quote_message", columnDefinition = "TEXT")
    private String quoteMessage;

    @Column(name = "business_hours", length = 100)
    private String businessHours;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
