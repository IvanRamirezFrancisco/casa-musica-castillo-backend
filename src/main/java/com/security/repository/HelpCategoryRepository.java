package com.security.repository;

import com.security.entity.HelpCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HelpCategoryRepository extends JpaRepository<HelpCategory, Long> {
    
    List<HelpCategory> findByActiveTrueOrderByDisplayOrderAsc();
    
    Optional<HelpCategory> findBySlug(String slug);
    
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);
}
