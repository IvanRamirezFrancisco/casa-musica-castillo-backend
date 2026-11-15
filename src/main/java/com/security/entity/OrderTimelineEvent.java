package com.security.entity;

import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "order_timeline_events", schema = "sales")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderTimelineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_order_status")
    private OrderStatus previousOrderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_order_status")
    private OrderStatus newOrderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_payment_status")
    private PaymentStatus previousPaymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_payment_status")
    private PaymentStatus newPaymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_shipping_status")
    private ShippingStatus previousShippingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_shipping_status")
    private ShippingStatus newShippingStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actorUser;

    @Column(name = "actor_type")
    private String actorType; // CUSTOMER, ADMIN, SYSTEM, WEBHOOK

    @Column(name = "source")
    private String source; // CHECKOUT, ADMIN_PANEL, PAYMENT_PROOF, MERCADO_PAGO, ORDER_SERVICE, SHIPPING

    @Column(name = "visibility")
    private String visibility; // CUSTOMER, ADMIN, BOTH

    @Column(name = "metadata_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (visibility == null) {
            visibility = "BOTH";
        }
    }
}
