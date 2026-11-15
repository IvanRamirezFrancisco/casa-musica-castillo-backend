package com.security.repository;

import com.security.entity.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio para Brand
 */
@Repository
public interface BrandRepository extends JpaRepository<Brand, Long> {

        /**
         * Buscar marca por nombre (case insensitive)
         */
        Optional<Brand> findByNameIgnoreCase(String name);

        /**
         * Carga múltiples marcas por nombre en una sola query — anti N+1 para CSV
         * import
         */
        List<Brand> findByNameIgnoreCaseIn(List<String> names);

        /**
         * Verificar si existe una marca con ese nombre (excluyendo un ID)
         */
        boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

        /**
         * Buscar marcas activas
         */
        List<Brand> findByActiveTrue();

        /**
         * Buscar marcas con paginación
         */
        Page<Brand> findAll(Pageable pageable);

        /**
         * Buscar marcas por nombre con paginación
         */
        Page<Brand> findByNameContainingIgnoreCase(String name, Pageable pageable);

        /**
         * Buscar marcas por estado activo con paginación
         */
        Page<Brand> findByActive(Boolean active, Pageable pageable);

        /**
         * Búsqueda avanzada
         * Usa nativeQuery para evitar el bug de Hibernate 6 + PostgreSQL con parámetros
         * null en LIKE
         */
        @Query(value = "SELECT * FROM catalog.brands b WHERE " +
                        "(:name IS NULL OR LOWER(CAST(b.name AS text)) LIKE LOWER('%' || :name || '%')) AND " +
                        "(:active IS NULL OR b.active = :active) AND " +
                        "(:countryOrigin IS NULL OR LOWER(CAST(b.country_origin AS text)) LIKE LOWER('%' || :countryOrigin || '%'))", countQuery = "SELECT COUNT(*) FROM catalog.brands b WHERE "
                                        +
                                        "(:name IS NULL OR LOWER(CAST(b.name AS text)) LIKE LOWER('%' || :name || '%')) AND "
                                        +
                                        "(:active IS NULL OR b.active = :active) AND " +
                                        "(:countryOrigin IS NULL OR LOWER(CAST(b.country_origin AS text)) LIKE LOWER('%' || :countryOrigin || '%'))", nativeQuery = true)
        Page<Brand> searchBrands(@Param("name") String name,
                        @Param("active") Boolean active,
                        @Param("countryOrigin") String countryOrigin,
                        Pageable pageable);

        /**
         * Contar marcas activas
         */
        long countByActiveTrue();

        /**
         * Obtener marcas con más productos
         */
        @Query("SELECT b FROM Brand b ORDER BY b.productCount DESC")
        List<Brand> findTopBrandsByProductCount(Pageable pageable);
}
