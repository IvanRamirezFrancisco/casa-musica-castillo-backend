package com.security.repository;

import com.security.entity.Product;
import com.security.repository.projection.ProductRecommendationProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repositorio de sólo lectura para consultar la vista de recomendaciones activas.
 */
@org.springframework.stereotype.Repository
public interface PublicProductRecommendationRepository extends Repository<Product, Long> {

    @Query(value = """
            SELECT
                r.recommended_product_id AS productId,
                r.recommended_product_sku AS sku,
                r.recommended_product_name AS name,
                r.recommended_product_description AS description,
                r.recommended_product_image_url AS imageUrl,
                r.recommended_category_id AS categoryId,
                r.recommended_category_name AS categoryName,
                r.regular_price AS regularPrice,
                r.effective_price AS effectivePrice,
                r.discount_price AS discountPrice,
                r.stock AS stock,
                CASE
                    WHEN r.discount_price IS NOT NULL
                     AND r.discount_price > 0
                     AND r.discount_price < r.regular_price
                    THEN TRUE
                    ELSE FALSE
                END AS hasDiscount
            FROM analytics.vw_active_product_recommendations r
            WHERE r.antecedent_product_id = :productId
              AND r.recommended_product_id <> :productId
            ORDER BY
                r.priority ASC,
                r.lift DESC,
                r.confidence DESC
            LIMIT 4
            """, nativeQuery = true)
    List<ProductRecommendationProjection> findRecommendationsByProductId(@Param("productId") Long productId);

}
