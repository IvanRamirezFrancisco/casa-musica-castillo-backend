package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entidad para votos de utilidad en reseñas
 */
@Entity
@Table(name = "review_helpfulness", schema = "catalog", uniqueConstraints = {
                @UniqueConstraint(name = "uk_review_user", columnNames = { "review_id", "user_id" })
}, indexes = {
                @Index(name = "idx_helpfulness_review", columnList = "review_id"),
                @Index(name = "idx_helpfulness_user", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewHelpfulness {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        /**
         * Reseña votada
         */
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "review_id", nullable = false)
        @NotNull
        private ProductReview review;

        /**
         * Usuario que votó
         */
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "user_id", nullable = false)
        @NotNull
        private User user;

        /**
         * Tipo de voto: HELPFUL (útil) o NOT_HELPFUL (no útil)
         */
        @Column(name = "is_helpful", nullable = false)
        private Boolean isHelpful;

        @CreationTimestamp
        @Column(name = "created_at", nullable = false, updatable = false)
        private LocalDateTime createdAt;
}
