package com.security.service;

import com.security.dto.BrandDTO;
import com.security.entity.Brand;
import com.security.exception.ResourceNotFoundException;
import com.security.exception.DuplicateResourceException;
import com.security.repository.BrandRepository;
import com.security.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
import com.security.util.SlugUtils;

/**
 * Servicio para gestión de marcas
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class BrandService {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final StorageService storageService;

    /**
     * Obtener todas las marcas con paginación
     */
    @Transactional(readOnly = true)
    public BrandDTO.BrandListResponse getAllBrands(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Brand> brandPage = brandRepository.findAll(pageable);

        List<BrandDTO.BrandResponse> brands = brandPage.getContent().stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return BrandDTO.BrandListResponse.builder()
                .brands(brands)
                .totalBrands((int) brandPage.getTotalElements())
                .currentPage(page)
                .totalPages(brandPage.getTotalPages())
                .build();
    }

    /**
     * Buscar marcas con filtros
     */
    @Transactional(readOnly = true)
    public BrandDTO.BrandListResponse searchBrands(String name, Boolean active, String countryOrigin,
            int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Page<Brand> brandPage = brandRepository.searchBrands(name, active, countryOrigin, pageable);

        List<BrandDTO.BrandResponse> brands = brandPage.getContent().stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return BrandDTO.BrandListResponse.builder()
                .brands(brands)
                .totalBrands((int) brandPage.getTotalElements())
                .currentPage(page)
                .totalPages(brandPage.getTotalPages())
                .build();
    }

    /**
     * Obtener marcas activas (para select en productos)
     */
    @Transactional(readOnly = true)
    public List<BrandDTO.BrandBasicInfo> getActiveBrands() {
        return brandRepository.findByActiveTrue().stream()
                .map(this::convertToBasicInfo)
                .collect(Collectors.toList());
    }

    /**
     * Obtener marca por ID
     */
    @Transactional(readOnly = true)
    public BrandDTO.BrandResponse getBrandById(Long id) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + id));
        return convertToResponse(brand);
    }

    /**
     * Crear nueva marca
     */
    public BrandDTO.BrandResponse createBrand(BrandDTO.BrandRequest request) {
        log.info("Creando nueva marca: {}", request.getName());

        // Verificar si ya existe una marca con ese nombre
        if (brandRepository.findByNameIgnoreCase(request.getName()).isPresent()) {
            throw new DuplicateResourceException("Ya existe una marca con el nombre: " + request.getName());
        }

        Brand brand = Brand.builder()
                .name(request.getName().trim())
                .description(request.getDescription())
                .logoUrl(request.getLogoUrl())
                .websiteUrl(request.getWebsiteUrl())
                .countryOrigin(request.getCountryOrigin())
                .active(request.getActive() != null ? request.getActive() : true)
                .productCount(0L)
                .build();

        Brand savedBrand = brandRepository.save(brand);
        log.info("Marca creada exitosamente con ID: {}", savedBrand.getId());

        return convertToResponse(savedBrand);
    }

    /**
     * Actualizar marca existente
     */
    public BrandDTO.BrandResponse updateBrand(Long id, BrandDTO.BrandRequest request) {
        log.info("Actualizando marca ID: {}", id);

        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + id));

        // Verificar si el nuevo nombre ya existe en otra marca
        if (!brand.getName().equalsIgnoreCase(request.getName()) &&
                brandRepository.existsByNameIgnoreCaseAndIdNot(request.getName(), id)) {
            throw new DuplicateResourceException("Ya existe otra marca con el nombre: " + request.getName());
        }

        brand.setName(request.getName().trim());
        brand.setDescription(request.getDescription());
        brand.setLogoUrl(request.getLogoUrl());
        brand.setWebsiteUrl(request.getWebsiteUrl());
        brand.setCountryOrigin(request.getCountryOrigin());

        if (request.getActive() != null) {
            brand.setActive(request.getActive());
        }

        Brand updatedBrand = brandRepository.save(brand);
        log.info("Marca actualizada exitosamente: {}", updatedBrand.getName());

        return convertToResponse(updatedBrand);
    }

    /**
     * Eliminar marca (solo si no tiene productos asociados)
     */
    public void deleteBrand(Long id) {
        log.info("Intentando eliminar marca ID: {}", id);

        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + id));

        // Verificar que no tenga productos asociados
        long productCount = productRepository.countByBrandId(id);
        if (productCount > 0) {
            throw new IllegalStateException(
                    "No se puede eliminar la marca porque tiene " + productCount + " productos asociados. " +
                            "Primero elimine o reasigne los productos.");
        }

        brandRepository.delete(brand);
        log.info("Marca eliminada exitosamente: {}", brand.getName());
    }

    /**
     * Activar/Desactivar marca
     */
    public BrandDTO.BrandResponse toggleBrandStatus(Long id) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + id));

        brand.setActive(!brand.getActive());
        Brand updatedBrand = brandRepository.save(brand);

        log.info("Estado de marca {} cambiado a: {}", brand.getName(), brand.getActive());

        return convertToResponse(updatedBrand);
    }

    /**
     * Actualizar contador de productos de una marca
     */
    public void updateProductCount(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + brandId));

        long productCount = productRepository.countByBrandId(brandId);
        brand.setProductCount(productCount);
        brandRepository.save(brand);
    }

    /**
     * Subir logo de marca
     */
    public com.security.dto.StorageUploadResult uploadLogo(Long brandId, org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + brandId));

        if (brand.getLogoPublicId() != null) {
            storageService.delete(brand.getLogoPublicId());
        }

        String folderPath = SlugUtils.buildBrandFolder(brand.getId(), brand.getName());
        com.security.dto.StorageUploadResult result = storageService.uploadBrandImage(file, brandId, folderPath);
        
        brand.setLogoUrl(result.getSecureUrl());
        brand.setLogoPublicId(result.getPublicId());
        brand.setLogoProvider(result.getProvider());
        
        brandRepository.save(brand);
        return result;
    }

    /**
     * Eliminar logo de marca
     */
    public void deleteLogo(Long brandId) throws java.io.IOException {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + brandId));

        if (brand.getLogoPublicId() != null) {
            storageService.delete(brand.getLogoPublicId());
        }
        
        brand.setLogoUrl(null);
        brand.setLogoPublicId(null);
        brand.setLogoProvider(null);
        
        brandRepository.save(brand);
    }

    // ==================== CONVERSORES ====================

    private BrandDTO.BrandResponse convertToResponse(Brand brand) {
        return BrandDTO.BrandResponse.builder()
                .id(brand.getId())
                .name(brand.getName())
                .description(brand.getDescription())
                .logoUrl(brand.getLogoUrl())
                .logoProvider(brand.getLogoProvider())
                .logoPublicId(brand.getLogoPublicId())
                .websiteUrl(brand.getWebsiteUrl())
                .countryOrigin(brand.getCountryOrigin())
                .active(brand.getActive())
                .productCount(brand.getProductCount())
                .createdAt(brand.getCreatedAt())
                .updatedAt(brand.getUpdatedAt())
                .build();
    }

    private BrandDTO.BrandBasicInfo convertToBasicInfo(Brand brand) {
        return BrandDTO.BrandBasicInfo.builder()
                .id(brand.getId())
                .name(brand.getName())
                .logoUrl(brand.getLogoUrl())
                .logoProvider(brand.getLogoProvider())
                .active(brand.getActive())
                .build();
    }
}
