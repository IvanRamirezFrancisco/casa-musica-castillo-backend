package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entidad para reseñas de productos
 */
@Entity
@Table(name = "product_reviews", schema = "catalog", indexes = {
        @Index(name = "idx_review_product", columnList = "product_id"),
        @Index(name = "idx_review_user", columnList = "user_id"),
        @Index(name = "idx_review_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Producto reseñado
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @NotNull
    private Product product;

    /**
     * Usuario que escribió la reseña
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @NotNull
    private User user;

    /**
     * Orden asociada (para verificar compra)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    /**
     * Calificación (1-5 estrellas)
     */
    @NotNull
    @Min(value = 1, message = "La calificación mínima es 1")
    @Max(value = 5, message = "La calificación máxima es 5")
    @Column(nullable = false)
    private Integer rating;

    /**
     * Título de la reseña
     */
    @Size(max = 200, message = "El título no puede exceder 200 caracteres")
    @Column(length = 200)
    private String title;

    /**
     * Comentario detallado
     */
    @NotBlank(message = "El comentario es obligatorio")
    @Size(min = 10, max = 2000, message = "El comentario debe tener entre 10 y 2000 caracteres")
    @Column(columnDefinition = "TEXT", nullable = false)
    private String comment;

    /**
     * Indica si es una compra verificada
     */
    @Column(name = "verified_purchase", nullable = false)
    private Boolean verifiedPurchase = false;

    /**
     * Estado: PENDING, APPROVED, REJECTED
     */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    /**
     * Contador de votos útiles
     */
    @Column(name = "helpful_count", nullable = false)
    private Integer helpfulCount = 0;

    /**
     * Contador de votos no útiles
     */
    @Column(name = "not_helpful_count", nullable = false)
    private Integer notHelpfulCount = 0;

    /**
     * Respuesta del vendedor/admin
     */
    @Column(name = "seller_response", columnDefinition = "TEXT")
    private String sellerResponse;

    /**
     * Fecha de respuesta del vendedor
     */
    @Column(name = "seller_response_at")
    private LocalDateTime sellerResponseAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Verifica si la reseña está aprobada
     */
    public boolean isApproved() {
        return "APPROVED".equals(status);
    }

    /**
     * Incrementa el contador de votos útiles
     */
    public void incrementHelpfulCount() {
        this.helpfulCount++;
    }

    /**
     * Incrementa el contador de votos no útiles
     */
    public void incrementNotHelpfulCount() {
        this.notHelpfulCount++;
    }
}
