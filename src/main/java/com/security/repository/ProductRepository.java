package com.security.repository;

import com.security.dto.admin.TopProductDTO;
import com.security.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    List<Product> findByActiveTrue();

    List<Product> findByFeaturedTrue();

    Page<Product> findByActiveTrue(Pageable pageable);

    Page<Product> findByCategoryIdAndActiveTrue(Long categoryId, Pageable pageable);

    @Query(value = "SELECT * FROM catalog.products p WHERE p.active = true AND " +
            "(LOWER(CAST(p.name AS text)) LIKE LOWER('%' || :keyword || '%') OR " +
            "LOWER(CAST(p.description AS text)) LIKE LOWER('%' || :keyword || '%'))", countQuery = "SELECT COUNT(*) FROM catalog.products p WHERE p.active = true AND "
                    +
                    "(LOWER(CAST(p.name AS text)) LIKE LOWER('%' || :keyword || '%') OR " +
                    "LOWER(CAST(p.description AS text)) LIKE LOWER('%' || :keyword || '%'))", nativeQuery = true)
    Page<Product> searchProducts(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 🔍 BÚSQUEDA AVANZADA CON FILTROS MÚLTIPLES
     * Busca productos por nombre, SKU, descripción con filtros opcionales de marca,
     * categoría y estado
     */
    @Query(value = "SELECT * FROM catalog.products p WHERE " +
            "(:search IS NULL OR LOWER(CAST(p.name AS text)) LIKE LOWER('%' || :search || '%') " +
            "OR LOWER(CAST(p.sku AS text)) LIKE LOWER('%' || :search || '%') " +
            "OR LOWER(CAST(p.description AS text)) LIKE LOWER('%' || :search || '%')) " +
            "AND (:brandId IS NULL OR p.brand_id = :brandId) " +
            "AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "AND (CAST(:active AS boolean) IS NULL OR p.active = CAST(:active AS boolean))", countQuery = "SELECT COUNT(*) FROM catalog.products p WHERE "
                    +
                    "(:search IS NULL OR LOWER(CAST(p.name AS text)) LIKE LOWER('%' || :search || '%') "
                    +
                    "OR LOWER(CAST(p.sku AS text)) LIKE LOWER('%' || :search || '%') " +
                    "OR LOWER(CAST(p.description AS text)) LIKE LOWER('%' || :search || '%')) " +
                    "AND (:brandId IS NULL OR p.brand_id = :brandId) " +
                    "AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
                    "AND (CAST(:active AS boolean) IS NULL OR p.active = CAST(:active AS boolean))", nativeQuery = true)
    Page<Product> searchProductsWithFilters(
            @Param("search") String search,
            @Param("brandId") Long brandId,
            @Param("categoryId") Long categoryId,
            @Param("active") Boolean active,
            Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.stock < :threshold")
    List<Product> findLowStockProducts(@Param("threshold") Integer threshold);

    @Query("SELECT p FROM Product p ORDER BY p.salesCount DESC")
    List<Product> findTopSellingProducts(Pageable pageable);

    /**
     * Proyección segura para el dashboard: evita LazyInitializationException
     * al acceder a {@code Product.category} fuera de la sesión Hibernate.
     * Selecciona únicamente campos escalares propios de {@code Product}.
     */
    @Query("""
            SELECT new com.security.dto.admin.TopProductDTO(
                p.id,
                p.name,
                p.sku,
                p.price,
                p.salesCount,
                p.imageUrl
            )
            FROM Product p
            ORDER BY p.salesCount DESC
            """)
    List<TopProductDTO> findTopSellingProductDtos(Pageable pageable);

    boolean existsBySku(String sku);

    Long countByActiveTrue();

    Long countByCategoryId(Long categoryId);

    // Métodos para Brand
    Long countByBrandId(Long brandId);

    Page<Product> findByBrandId(Long brandId, Pageable pageable);

    List<Product> findByBrandId(Long brandId);

    /**
     * Anti-N+1: carga en UNA sola query todos los productos cuyos SKU
     * aparecen en el CSV, para poder hacer el upsert sin iterar con findBySku().
     */
    List<Product> findBySkuIn(List<String> skus);
}
