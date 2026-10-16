package com.security.repository;

import com.security.entity.Faq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaqRepository extends JpaRepository<Faq, Long> {
    
    @Query("SELECT f FROM Faq f WHERE f.active = true AND f.category.active = true ORDER BY f.category.displayOrder ASC, f.displayOrder ASC")
    List<Faq> findAllActivePublic();

    @Query("SELECT f FROM Faq f WHERE f.active = true AND f.featured = true AND f.category.active = true ORDER BY f.displayOrder ASC")
    List<Faq> findFeaturedPublic();
    
    @Query("SELECT f FROM Faq f WHERE f.active = true AND f.category.slug = :slug AND f.category.active = true ORDER BY f.displayOrder ASC")
    List<Faq> findByActiveCategorySlugPublic(@Param("slug") String slug);

    boolean existsByCategoryId(Long categoryId);
    
    @Query("SELECT COUNT(f) FROM Faq f WHERE f.category.id = :categoryId AND f.active = true")
    int countActiveFaqsByCategoryId(@Param("categoryId") Long categoryId);

    // Búsqueda jerárquica parametrizada (Puntos 41-45). PostgreSQL 'unaccent' puede ser invocado con function() si hace falta,
    // pero utilizaremos un LIKE básico parametrizado y delegaremos limpieza al backend.
    @Query("SELECT f FROM Faq f WHERE f.active = true AND f.category.active = true " +
           "AND (LOWER(f.question) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(f.answer) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(f.category.name) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY " +
           "  CASE WHEN LOWER(f.question) LIKE LOWER(CONCAT(:query, '%')) THEN 1 ELSE 2 END ASC, " +
           "  f.displayOrder ASC")
    List<Faq> searchActivePublic(@Param("query") String query);
}
