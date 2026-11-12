package com.security.repository;

import com.security.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio de <strong>solo-lectura</strong> para la API pública del
 * Storefront.
 *
 * <p>
 * <strong>Regla de Oro de Seguridad:</strong> En <em>todas</em> las consultas
 * de este repositorio, {@code p.active = true} está hardcodeado en JPQL.
 * Nunca se retorna un borrador o producto inactivo bajo ninguna circunstancia,
 * independientemente de los parámetros que lleguen desde el controlador.
 * </p>
 *
 * <p>
 * Se usa <strong>JPQL con parámetros nombrados</strong> (no SQL nativo) para
 * prevención automática de inyección SQL por parte de JPA/Hibernate.
 * </p>
 */
@Repository
public interface PublicProductRepository extends JpaRepository<Product, Long> {

    // ── 1. Últimos N productos activos ────────────────────────────────────────

    /**
     * Devuelve los últimos productos agregados que estén activos,
     * ordenados por fecha de creación descendente.
     *
     * <p>
     * El límite de 8 se controla con el {@link Pageable} que pasa el servicio
     * ({@code PageRequest.of(0, 8)}). El filtro {@code p.active = true} es
     * inmutable en la query.
     * </p>
     */
    @Query("""
            SELECT p FROM Product p
            LEFT JOIN FETCH p.category
            LEFT JOIN FETCH p.brand
            WHERE p.active = true
            ORDER BY p.createdAt DESC
            """)
    java.util.List<Product> findLatestActive(Pageable pageable);

    // ── 2. Productos destacados activos ───────────────────────────────────────

    /**
     * Devuelve productos marcados como featured AND active.
     * Paginado para soportar catálogos grandes sin riesgo DoS.
     */
    @Query("""
            SELECT p FROM Product p
            LEFT JOIN FETCH p.category
            LEFT JOIN FETCH p.brand
            WHERE p.active = true
              AND p.featured = true
            ORDER BY p.createdAt DESC
            """)
    Page<Product> findFeaturedActive(Pageable pageable);

    // ── 3. Catálogo con filtros opcionales ────────────────────────────────────

    /**
     * Endpoint de catálogo público con búsqueda y filtros opcionales.
     *
     * <p>
     * <strong>Seguridad contra inyección:</strong> Todos los parámetros son
     * enlazados por nombre ({@code :keyword}, {@code :categoryId},
     * {@code :brandId}). JPQL nunca concatena strings en la query.
     * </p>
     *
     * <p>
     * <strong>Seguridad lógica:</strong> {@code p.active = true} es la primera
     * condición y no puede ser sobreescrita por ningún parámetro del cliente.
     * </p>
     *
     * @param keyword    substring insensible a mayúsculas (null = sin filtro)
     * @param categoryId ID de categoría (null = sin filtro)
     * @param brandId    ID de marca (null = sin filtro)
     * @param pageable   paginación (el servicio limita a máx. 50 por página)
     */
    /**
     * Catálogo genérico SIN keyword. Soporta orden dinámico del Pageable (price, newest, featured, etc).
     */
    @Query(value = """
            SELECT p FROM Product p
            LEFT JOIN FETCH p.category cat
            LEFT JOIN FETCH p.brand br
            WHERE p.active = true
              AND (:categoryId IS NULL OR cat.id = :categoryId)
              AND (:brandId    IS NULL OR br.id  = :brandId)
            """,
           countQuery = """
            SELECT COUNT(p) FROM Product p
            LEFT JOIN p.category cat
            LEFT JOIN p.brand br
            WHERE p.active = true
              AND (:categoryId IS NULL OR cat.id = :categoryId)
              AND (:brandId    IS NULL OR br.id  = :brandId)
            """)
    Page<Product> findCatalogWithoutKeyword(
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            Pageable pageable);

    String CATALOG_KEYWORD_FILTER = """
            WHERE p.active = true
              AND (
                   CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                OR CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\'
                OR CAST(catalog_fold(p.sku) AS string)  LIKE CONCAT('%', CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                OR CAST(catalog_fold(p.sku) AS string)  LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\'
                OR (p.model IS NOT NULL AND (
                     CAST(catalog_fold(p.model) AS string) LIKE CONCAT('%', CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                  OR CAST(catalog_fold(p.model) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\'
                ))
                OR CAST(catalog_fold(cat.name) AS string) LIKE CONCAT('%', CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                OR CAST(catalog_fold(cat.name) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\'
                OR CAST(catalog_fold(br.name) AS string)  LIKE CONCAT('%', CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                OR CAST(catalog_fold(br.name) AS string)  LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\'
                OR CAST(catalog_fold(p.description) AS string) LIKE CONCAT('%', CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                OR CAST(catalog_fold(p.description) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\'
                OR (
                  :token1 IS NOT NULL
                  AND (
                       CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:token1 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(p.sku) AS string) LIKE CONCAT('%', CAST(:token1 AS string), '%') ESCAPE '\\'
                    OR (p.model IS NOT NULL AND CAST(catalog_fold(p.model) AS string) LIKE CONCAT('%', CAST(:token1 AS string), '%') ESCAPE '\\')
                    OR CAST(catalog_fold(cat.name) AS string) LIKE CONCAT('%', CAST(:token1 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(br.name) AS string) LIKE CONCAT('%', CAST(:token1 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(p.description) AS string) LIKE CONCAT('%', CAST(:token1 AS string), '%') ESCAPE '\\'
                  )
                  AND (
                    :token2 IS NULL
                    OR CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:token2 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(p.sku) AS string) LIKE CONCAT('%', CAST(:token2 AS string), '%') ESCAPE '\\'
                    OR (p.model IS NOT NULL AND CAST(catalog_fold(p.model) AS string) LIKE CONCAT('%', CAST(:token2 AS string), '%') ESCAPE '\\')
                    OR CAST(catalog_fold(cat.name) AS string) LIKE CONCAT('%', CAST(:token2 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(br.name) AS string) LIKE CONCAT('%', CAST(:token2 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(p.description) AS string) LIKE CONCAT('%', CAST(:token2 AS string), '%') ESCAPE '\\'
                  )
                  AND (
                    :token3 IS NULL
                    OR CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:token3 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(p.sku) AS string) LIKE CONCAT('%', CAST(:token3 AS string), '%') ESCAPE '\\'
                    OR (p.model IS NOT NULL AND CAST(catalog_fold(p.model) AS string) LIKE CONCAT('%', CAST(:token3 AS string), '%') ESCAPE '\\')
                    OR CAST(catalog_fold(cat.name) AS string) LIKE CONCAT('%', CAST(:token3 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(br.name) AS string) LIKE CONCAT('%', CAST(:token3 AS string), '%') ESCAPE '\\'
                    OR CAST(catalog_fold(p.description) AS string) LIKE CONCAT('%', CAST(:token3 AS string), '%') ESCAPE '\\'
                  )
                )
              )
              AND (:categoryId IS NULL OR cat.id = :categoryId)
              AND (:brandId    IS NULL OR br.id  = :brandId)
            """;

    /**
     * Conserva el ranking y la paginación del catálogo con búsqueda.
     * El Pageable debe llegar sin orden para mantener el ORDER BY de relevancia.
     */
    default Page<Product> findCatalogWithKeyword(
            String exactOriginal, String escapedOriginal, String exactCanonical, String escapedCanonical,
            String token1, String token2, String token3, Long categoryIntentId, Long brandIntentId,
            Long categoryId, Long brandId, Pageable pageable) {
        List<Product> products = findCatalogKeywordRows(exactOriginal, escapedOriginal, exactCanonical,
                escapedCanonical, token1, token2, token3, categoryIntentId, brandIntentId,
                categoryId, brandId, pageable);
        // El conteo solo recibe filtros. Los parámetros de ranking pertenecen a la consulta de contenido.
        return PageableExecutionUtils.getPage(products, pageable, () -> countCatalogKeywordMatches(
                escapedOriginal, escapedCanonical, token1, token2, token3, categoryId, brandId));
    }

    @Query("""
            SELECT p FROM Product p
            LEFT JOIN FETCH p.category cat
            LEFT JOIN FETCH p.brand br
            """ + CATALOG_KEYWORD_FILTER + """
            ORDER BY 
              CASE 
                   WHEN CAST(catalog_fold(p.sku) AS string) IN (CAST(:exactOriginal AS string), CAST(:exactCanonical AS string)) THEN 1
                   WHEN CAST(catalog_fold(p.name) AS string) IN (CAST(:exactOriginal AS string), CAST(:exactCanonical AS string)) THEN 2
                   WHEN p.model IS NOT NULL AND CAST(catalog_fold(p.model) AS string) IN (CAST(:exactOriginal AS string), CAST(:exactCanonical AS string)) THEN 3
                   WHEN CAST(catalog_fold(p.name) AS string) LIKE CONCAT(CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                     OR CAST(catalog_fold(p.name) AS string) LIKE CONCAT(CAST(:escapedCanonical AS string), '%') ESCAPE '\\'
                     OR CAST(catalog_fold(p.sku) AS string) LIKE CONCAT(CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                     OR CAST(catalog_fold(p.sku) AS string) LIKE CONCAT(CAST(:escapedCanonical AS string), '%') ESCAPE '\\' THEN 4
                   WHEN CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                     OR CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\'
                     OR (
                       :token1 IS NOT NULL AND :token2 IS NOT NULL
                       AND CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:token1 AS string), '%') ESCAPE '\\'
                       AND CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:token2 AS string), '%') ESCAPE '\\'
                     ) THEN 5
                   WHEN (:brandIntentId IS NOT NULL AND br.id = :brandIntentId)
                     OR CAST(catalog_fold(br.name) AS string) IN (CAST(:exactOriginal AS string), CAST(:exactCanonical AS string))
                     OR CAST(catalog_fold(br.name) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\' THEN 6
                   WHEN (:categoryIntentId IS NOT NULL AND cat.id = :categoryIntentId)
                     OR CAST(catalog_fold(cat.name) AS string) IN (CAST(:exactOriginal AS string), CAST(:exactCanonical AS string))
                     OR CAST(catalog_fold(cat.name) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\' THEN 7
                   WHEN (:token1 IS NOT NULL AND CAST(catalog_fold(p.name) AS string) LIKE CONCAT('%', CAST(:token1 AS string), '%') ESCAPE '\\')
                     OR (p.model IS NOT NULL AND CAST(catalog_fold(p.model) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\') THEN 8
                   WHEN CAST(catalog_fold(p.description) AS string) LIKE CONCAT('%', CAST(:escapedOriginal AS string), '%') ESCAPE '\\'
                     OR CAST(catalog_fold(p.description) AS string) LIKE CONCAT('%', CAST(:escapedCanonical AS string), '%') ESCAPE '\\' THEN 9
                   ELSE 10 END ASC,
              CASE WHEN p.stock > 0 THEN 0 ELSE 1 END ASC,
              p.featured DESC,
              p.createdAt DESC,
              p.id ASC
            """)
    List<Product> findCatalogKeywordRows(
            @Param("exactOriginal") String exactOriginal,
            @Param("escapedOriginal") String escapedOriginal,
            @Param("exactCanonical") String exactCanonical,
            @Param("escapedCanonical") String escapedCanonical,
            @Param("token1") String token1,
            @Param("token2") String token2,
            @Param("token3") String token3,
            @Param("categoryIntentId") Long categoryIntentId,
            @Param("brandIntentId") Long brandIntentId,
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            Pageable pageable);

    @Query("""
            SELECT COUNT(p) FROM Product p
            LEFT JOIN p.category cat
            LEFT JOIN p.brand br
            """ + CATALOG_KEYWORD_FILTER)
    long countCatalogKeywordMatches(
            @Param("escapedOriginal") String escapedOriginal,
            @Param("escapedCanonical") String escapedCanonical,
            @Param("token1") String token1,
            @Param("token2") String token2,
            @Param("token3") String token3,
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId);

    // ── 4. Detalle de producto activo ─────────────────────────────────────────

    /**
     * Busca un producto por ID <strong>solo si está activo</strong>.
     *
     * <p>
     * Si el producto existe pero {@code active = false}, retorna
     * {@link Optional#empty()} — el servicio lanzará un 404, nunca expondrá un
     * borrador al público.
     * </p>
     */
    @Query("""
            SELECT p FROM Product p
            LEFT JOIN FETCH p.category
            LEFT JOIN FETCH p.brand
            LEFT JOIN FETCH p.images
            LEFT JOIN FETCH p.customAttributes
            WHERE p.id = :id
              AND p.active = true
            """)
    Optional<Product> findActiveById(@Param("id") Long id);
}
