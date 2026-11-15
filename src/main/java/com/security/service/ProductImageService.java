package com.security.service;

import com.security.dto.ProductImageResponse;
import com.security.dto.StorageUploadResult;
import com.security.entity.Product;
import com.security.entity.ProductImage;
import com.security.repository.ProductImageRepository;
import com.security.repository.ProductRepository;
import com.security.util.SlugUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProductImageService {

    private static final Logger logger = LoggerFactory.getLogger(ProductImageService.class);

    private final ProductImageRepository productImageRepository;
    private final ProductRepository productRepository;
    private final StorageService storageService;

    public ProductImageService(
            ProductImageRepository productImageRepository,
            ProductRepository productRepository,
            StorageService storageService) {
        this.productImageRepository = productImageRepository;
        this.productRepository = productRepository;
        this.storageService = storageService;
    }

    private ProductImageResponse mapToResponse(ProductImage img) {
        return ProductImageResponse.builder()
                .id(img.getId())
                .productId(img.getProduct().getId())
                .imageUrl(img.getImageUrl())
                .contentType(img.getContentType())
                .fileSizeBytes(img.getFileSizeBytes())
                .provider(img.getProvider())
                .isPrimary(img.getIsPrimary())
                .displayOrder(img.getDisplayOrder())
                .createdAt(img.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<ProductImageResponse> getProductImages(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new IllegalArgumentException("Producto no encontrado");
        }

        List<ProductImage> images = productImageRepository.findByProductIdOrderByDisplayOrderAsc(productId);
        
        return images.stream()
                .sorted(Comparator.comparing(ProductImage::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProductImage::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProductImageResponse uploadProductImage(Long productId, MultipartFile file, Long uploadedBy) {
        // 1. Validar producto
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado con ID: " + productId));

        // 2. Validar límite
        long imageCount = productImageRepository.countByProductId(productId);
        if (imageCount >= 8) {
            throw new IllegalStateException("Máximo 8 imágenes por producto.");
        }

        // 3. Subir a Cloudinary
        String folderPath = SlugUtils.buildProductFolder(product.getId(), product.getName());
        StorageUploadResult result;
        try {
            result = storageService.uploadProductImage(file, productId, folderPath);
        } catch (Exception e) {
            logger.error("Error al subir archivo a Storage", e);
            throw new RuntimeException("Error al subir archivo: " + e.getMessage());
        }

        try {
            // 4. Crear entidad ProductImage
            ProductImage productImage = new ProductImage();
            // productImage.setProduct(product); // Se establece con addImage para sincronización
            productImage.setImageUrl(result.getSecureUrl());
            productImage.setPublicId(result.getPublicId());
            productImage.setStoredFilename(result.getStoredFilename());
            productImage.setContentType(result.getContentType());
            productImage.setFileSizeBytes(result.getFileSizeBytes());
            productImage.setProvider(result.getProvider());
            
            // 5. Configurar Primary y Order
            if (imageCount == 0) {
                productImage.setIsPrimary(true);
                product.setImageUrl(result.getSecureUrl());
            } else {
                productImage.setIsPrimary(false);
            }
            
            Integer maxDisplayOrder = productImageRepository.findMaxDisplayOrderByProductId(productId);
            int nextOrder = (maxDisplayOrder != null && maxDisplayOrder > 0) ? maxDisplayOrder + 1 : 1;
            productImage.setDisplayOrder(nextOrder);

            // 6. Sincronizar relación bidireccional (previene orphanRemoval)
            product.addImage(productImage);

            // 7. Guardar explícitamente y forzar escritura a base de datos
            ProductImage savedImage = productImageRepository.saveAndFlush(productImage);
            
            // Si hubo cambio en el producto (imageUrl principal)
            if (imageCount == 0) {
                productRepository.saveAndFlush(product);
            }

            return mapToResponse(savedImage);

        } catch (Exception e) {
            logger.error("Error al persistir la imagen en DB. Procediendo con limpieza de asset en Cloudinary", e);
            // 8. Intentar limpieza reactiva en Cloudinary
            if (result != null && result.getPublicId() != null) {
                try {
                    storageService.delete(result.getPublicId());
                } catch (Exception cleanupException) {
                    logger.warn("No se pudo eliminar el asset huérfano en Cloudinary: " + result.getPublicId(), cleanupException);
                }
            }
            throw new RuntimeException("No se pudo guardar la imagen en la base de datos. Se intentó limpiar el archivo subido automáticamente.");
        }
    }

    @Transactional
    public void deleteImage(Long productId, Long imageId) {
        ProductImage image = productImageRepository.findById(imageId)
                .orElseThrow(() -> new IllegalArgumentException("Imagen no encontrada"));

        if (!image.getProduct().getId().equals(productId)) {
            throw new IllegalArgumentException("La imagen no pertenece a este producto");
        }

        Product product = image.getProduct();

        // 1. Eliminar de Cloudinary
        try {
            storageService.delete(image.getPublicId());
        } catch (Exception e) {
            logger.warn("No se pudo eliminar el archivo de Cloudinary para la imagen " + imageId, e);
            // No bloqueamos la eliminación en base de datos si falla la eliminación remota
        }

        // 2. Sincronización bidireccional
        boolean wasPrimary = Boolean.TRUE.equals(image.getIsPrimary());
        product.removeImage(image);
        
        // 3. Eliminar de base de datos
        productImageRepository.delete(image);
        productImageRepository.flush(); // Forzamos para que el count / find funcionen bien a continuación

        // 4. Obtener imágenes restantes ordenadas por el displayOrder viejo
        List<ProductImage> remainingImages = productImageRepository.findByProductIdOrderByDisplayOrderAsc(productId);
        
        // 5. Normalizar displayOrder (1, 2, 3...)
        int currentOrder = 1;
        for (ProductImage img : remainingImages) {
            img.setDisplayOrder(currentOrder++);
        }
        productImageRepository.saveAllAndFlush(remainingImages);

        // 6. Si era primaria, asignar nueva primaria
        if (wasPrimary) {
            if (!remainingImages.isEmpty()) {
                ProductImage newPrimary = remainingImages.get(0);
                newPrimary.setIsPrimary(true);
                productImageRepository.saveAndFlush(newPrimary);
                product.setImageUrl(newPrimary.getImageUrl());
            } else {
                product.setImageUrl(null);
            }
            productRepository.saveAndFlush(product);
        }
    }

    @Transactional
    public void setPrimaryImage(Long productId, Long imageId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));

        ProductImage newPrimary = productImageRepository.findById(imageId)
                .orElseThrow(() -> new IllegalArgumentException("Imagen no encontrada"));

        if (!newPrimary.getProduct().getId().equals(productId)) {
            throw new IllegalArgumentException("La imagen no pertenece a este producto");
        }

        // Quitar primary a todas
        List<ProductImage> allImages = productImageRepository.findByProductIdOrderByDisplayOrderAsc(productId);
        for (ProductImage img : allImages) {
            if (Boolean.TRUE.equals(img.getIsPrimary())) {
                img.setIsPrimary(false);
                productImageRepository.save(img);
            }
        }

        // Asignar primary
        newPrimary.setIsPrimary(true);
        productImageRepository.save(newPrimary);

        // Actualizar producto
        product.setImageUrl(newPrimary.getImageUrl());
        productRepository.saveAndFlush(product);
    }
}
