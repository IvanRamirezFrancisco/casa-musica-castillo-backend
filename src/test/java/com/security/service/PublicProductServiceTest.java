package com.security.service;

import com.security.dto.public_api.PublicProductDTO;
import com.security.entity.Brand;
import com.security.entity.Category;
import com.security.entity.Product;
import com.security.repository.BrandRepository;
import com.security.repository.CategoryRepository;
import com.security.repository.PublicProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class PublicProductServiceTest {

    private PublicProductRepository repository;
    private SearchNormalizationService normalizationService;
    private CategoryRepository categoryRepository;
    private BrandRepository brandRepository;
    private PublicProductService service;

    @BeforeEach
    void setUp() {
        repository = mock(PublicProductRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        brandRepository = mock(BrandRepository.class);
        normalizationService = new SearchNormalizationService();
        service = new PublicProductService(repository, normalizationService, categoryRepository, brandRepository);
    }

    @Test
    void testSingularYCategoria() {
        Product p = createMockProduct(1L, "Jarana Huasteca", "Jaranas", "Artesanal");
        Page<Product> mockPage = new PageImpl<>(List.of(p));
        
        when(repository.findCatalogWithKeyword(eq("jaranas"), eq("jaranas"), eq("jarana"), eq("jarana"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(mockPage);
            
        Page<PublicProductDTO> result = service.getCatalog("jaranas", null, null, 0, 10, "newest");
        
        assertEquals(1, result.getTotalElements());
        assertEquals("Jarana Huasteca", result.getContent().get(0).name());
        assertEquals("Jaranas", result.getContent().get(0).category().name());
    }

    @Test
    void testCamposMarca() {
        Product p = createMockProduct(2L, "Guitarra Acústica", "Guitarras", "Yamaha");
        Page<Product> mockPage = new PageImpl<>(List.of(p));
        
        when(repository.findCatalogWithKeyword(eq("yamaha"), eq("yamaha"), eq("yamaha"), eq("yamaha"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(mockPage);
            
        Page<PublicProductDTO> result = service.getCatalog("Yamaha", null, null, 0, 10, "newest");
        
        assertEquals(1, result.getTotalElements());
        assertEquals("Yamaha", result.getContent().get(0).brand().name());
    }

    @Test
    void testPaginacionEstableYMaximo() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        
        when(repository.findCatalogWithKeyword(any(), any(), any(), any(), any(), any(), any(), any(), pageableCaptor.capture()))
            .thenReturn(new PageImpl<>(List.of()));
            
        service.getCatalog("test", null, null, 0, 1000, "newest");
        
        Pageable captured = pageableCaptor.getValue();
        assertEquals(50, captured.getPageSize());
        assertEquals(0, captured.getPageNumber());
    }
    
    @Test
    void testRelevanciaDelegaARepositorio() {
        ArgumentCaptor<String> exactOriginalCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> escapedOriginalCaptor = ArgumentCaptor.forClass(String.class);
        
        when(repository.findCatalogWithKeyword(exactOriginalCaptor.capture(), escapedOriginalCaptor.capture(), any(), any(), any(), any(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
            
        service.getCatalog("100% Yamaha_SKU", null, null, 0, 10, "newest");
        
        assertEquals("100% yamaha_sku", exactOriginalCaptor.getValue());
        assertEquals("100\\% yamaha\\_sku", escapedOriginalCaptor.getValue());
    }

    private Product createMockProduct(Long id, String name, String categoryName, String brandName) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setSku("SKU-" + id);
        p.setPrice(new BigDecimal("1000.00"));
        p.setStock(10);
        
        if (categoryName != null) {
            Category c = new Category();
            c.setId(100L);
            c.setName(categoryName);
            p.setCategory(c);
        }
        
        if (brandName != null) {
            Brand b = new Brand();
            b.setId(200L);
            b.setName(brandName);
            p.setBrand(b);
        }
        
        return p;
    }
}
