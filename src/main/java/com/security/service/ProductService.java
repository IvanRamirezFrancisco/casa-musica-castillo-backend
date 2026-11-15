package com.security.service;

import com.security.dto.ProductAttributeDTO;
import com.security.dto.ProductDTO;
import com.security.dto.ProductImageDTO;
import com.security.entity.Brand;
import com.security.entity.Category;
import com.security.entity.Product;
import com.security.entity.ProductAttribute;
import com.security.entity.ProductImage;
import com.security.repository.BrandRepository;
import com.security.repository.CategoryRepository;
import com.security.repository.ProductAttributeRepository;
import com.security.repository.ProductImageRepository;
import com.security.repository.ProductRepository;
import com.security.service.AuditLogService;
import com.security.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private ProductAttributeRepository productAttributeRepository;

    @Autowired
    private AuditLogService auditLogService;

    /**
     * Obtener todos los productos
     */
    public List<ProductDTO> getAllProducts() {
        List<Product> products = productRepository.findAll();
        return products.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Obtener productos paginados
     */
    public Page<ProductDTO> getAllProductsPaginated(Pageable pageable) {
        Page<Product> productsPage = productRepository.findAll(pageable);
        return productsPage.map(this::convertToDTO);
    }

    /**
     * Obtener producto por ID
     */
    public ProductDTO getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Producto no encontrado con ID: " + id));
        return convertToDTO(product);
    }

    /**
     * Crear nuevo producto
     */
    public ProductDTO createProduct(ProductDTO productDTO) {
        logger.info("=== INICIANDO CREACIÓN DE PRODUCTO ===");
        logger.info("Nombre del producto: {}", LogSanitizer.sanitize(productDTO.getName()));
        logger.info("Custom Attributes recibidos: {}",
                productDTO.getCustomAttributes() != null ? productDTO.getCustomAttributes().size() : 0);

        if (productDTO.getCustomAttributes() != null && !productDTO.getCustomAttributes().isEmpty()) {
            logger.info("Detalles de los atributos:");
            for (int i = 0; i < productDTO.getCustomAttributes().size(); i++) {
                ProductAttributeDTO attr = productDTO.getCustomAttributes().get(i);
                logger.info("  Atributo {}: key='{}', value='{}'", i,
                        LogSanitizer.sanitize(attr.getKey()), LogSanitizer.sanitize(attr.getValue()));
            }
        }

        // Validar categoría
        Category category = categoryRepository.findById(productDTO.getCategoryId())
                .orElseThrow(
                        () -> new RuntimeException("Categoría no encontrada con ID: " + productDTO.getCategoryId()));

        // Validar marca (opcional)
        Brand brand = null;
        if (productDTO.getBrandId() != null) {
            brand = brandRepository.findById(productDTO.getBrandId())
                    .orElseThrow(() -> new RuntimeException("Marca no encontrada con ID: " + productDTO.getBrandId()));
        }

        Product product = new Product();
        product.setName(productDTO.getName());
        product.setDescription(productDTO.getDescription());
        product.setPrice(productDTO.getPrice());
        product.setDiscountPrice(productDTO.getDiscountPrice());
        product.setStock(productDTO.getStock());
        product.setImageUrl(productDTO.getImageUrl());
        // SKU: si el frontend lo omitió (creación manual) se genera después del save
        // para poder usar el ID auto-incremental.
        // CSV import siempre envía el SKU del archivo → se respeta tal cual.
        if (productDTO.getSku() != null && !productDTO.getSku().isBlank()) {
            product.setSku(productDTO.getSku());
        } else {
            product.setSku("__PENDING__"); // placeholder temporal hasta obtener el ID
        }
        product.setCategory(category);
        product.setBrand(brand);
        product.setModel(productDTO.getModel());
        product.setWeight(productDTO.getWeight());
        product.setDimensions(productDTO.getDimensions());
        product.setActive(productDTO.getActive() != null ? productDTO.getActive() : true);
        product.setFeatured(productDTO.getFeatured() != null ? productDTO.getFeatured() : false);

        // NUEVO: Descripción detallada (HTML)
        product.setDetailedDescription(productDTO.getDetailedDescription());

        logger.info("Guardando producto en base de datos...");
        Product savedProduct = productRepository.save(product);
        logger.info("Producto guardado con ID: {}", savedProduct.getId());

        // --- Generar SKU automático si era manual (sin SKU del frontend) ---
        if (productDTO.getSku() == null || productDTO.getSku().isBlank()) {
            String generatedSku = generateSku(category, brand, savedProduct.getId());
            savedProduct.setSku(generatedSku);
            savedProduct = productRepository.save(savedProduct);
            logger.info("SKU autogenerado: {}", generatedSku);
        }

        // Guardar galería de imágenes adicionales
        if (productDTO.getImages() != null && !productDTO.getImages().isEmpty()) {
            logger.info("Guardando {} imágenes...", productDTO.getImages().size());
            saveProductImages(savedProduct, productDTO.getImages());
        }

        // NUEVO: Guardar atributos dinámicos
        if (productDTO.getCustomAttributes() != null && !productDTO.getCustomAttributes().isEmpty()) {
            logger.info("Guardando {} atributos dinámicos...", productDTO.getCustomAttributes().size());
            saveProductAttributes(savedProduct, productDTO.getCustomAttributes());
            logger.info("Atributos guardados exitosamente");
        } else {
            logger.warn("No se recibieron customAttributes o la lista está vacía");
        }

        // Actualizar contador de productos de la marca
        if (brand != null) {
            brandRepository.findById(brand.getId()).ifPresent(b -> {
                b.setProductCount(b.getProductCount() + 1);
                brandRepository.save(b);
            });
        }

        logger.info("=== PRODUCTO CREADO EXITOSAMENTE ===");
        return convertToDTO(savedProduct);
    }

    /**
     * Actualizar producto existente
     */
    public ProductDTO updateProduct(Long id, ProductDTO productDTO) {
        logger.info("=== INICIANDO ACTUALIZACIÓN DE PRODUCTO ===");
        logger.info("Product ID: {}", id);
        logger.info("Custom Attributes recibidos: {}",
                productDTO.getCustomAttributes() != null ? productDTO.getCustomAttributes().size() : 0);

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Producto no encontrado con ID: " + id));

        Brand oldBrand = product.getBrand();

        // Capturar estado anterior para auditoría ANTES de modificar
        final BigDecimal oldPrice = product.getPrice();
        final Integer oldStock = product.getStock();
        final Boolean oldActive = product.getActive();

        // Actualizar categoría si se proporcionó y es diferente a la actual
        if (productDTO.getCategoryId() != null) {
            Long currentCategoryId = (product.getCategory() != null) ? product.getCategory().getId() : null;
            if (!productDTO.getCategoryId().equals(currentCategoryId)) {
                Category category = categoryRepository.findById(productDTO.getCategoryId())
                        .orElseThrow(() -> new RuntimeException(
                                "Categoría no encontrada con ID: " + productDTO.getCategoryId()));
                product.setCategory(category);
            }
        }

        // Actualizar marca si cambió
        if (productDTO.getBrandId() != null) {
            Brand newBrand = brandRepository.findById(productDTO.getBrandId())
                    .orElseThrow(() -> new RuntimeException("Marca no encontrada con ID: " + productDTO.getBrandId()));
            product.setBrand(newBrand);
        } else {
            product.setBrand(null);
        }

        // Actualizar campos
        if (productDTO.getName() != null) {
            product.setName(productDTO.getName());
        }
        if (productDTO.getDescription() != null) {
            product.setDescription(productDTO.getDescription());
        }
        if (productDTO.getPrice() != null) {
            product.setPrice(productDTO.getPrice());
        }
        if (productDTO.getDiscountPrice() != null) {
            product.setDiscountPrice(productDTO.getDiscountPrice());
        }
        if (productDTO.getStock() != null) {
            product.setStock(productDTO.getStock());
        }
        if (productDTO.getImageUrl() != null) {
            product.setImageUrl(productDTO.getImageUrl());
        }
        if (productDTO.getSku() != null) {
            product.setSku(productDTO.getSku());
        }
        if (productDTO.getModel() != null) {
            product.setModel(productDTO.getModel());
        }
        if (productDTO.getWeight() != null) {
            product.setWeight(productDTO.getWeight());
        }
        if (productDTO.getDimensions() != null) {
            product.setDimensions(productDTO.getDimensions());
        }
        if (productDTO.getActive() != null) {
            product.setActive(productDTO.getActive());
        }
        if (productDTO.getFeatured() != null) {
            product.setFeatured(productDTO.getFeatured());
        }
        // NUEVO: Actualizar descripción detallada
        if (productDTO.getDetailedDescription() != null) {
            product.setDetailedDescription(productDTO.getDetailedDescription());
        }

        Product updatedProduct = productRepository.save(product);

        // Auditoría de actualización de producto (stock/precio son los campos críticos)
        try {
            Map<String, Object> oldValues = new HashMap<>();
            oldValues.put("price", oldPrice);
            oldValues.put("stock", oldStock);
            oldValues.put("active", oldActive);

            Map<String, Object> newValues = new HashMap<>();
            newValues.put("price", updatedProduct.getPrice());
            newValues.put("stock", updatedProduct.getStock());
            newValues.put("active", updatedProduct.getActive());

            auditLogService.log(
                    "UPDATE", "PRODUCT_UPDATE", "PRODUCT",
                    updatedProduct.getId(), oldValues, newValues, "INFO", true);
        } catch (Exception auditEx) {
            logger.warn("⚠️ No se pudo registrar audit log para actualización de producto {}: {}",
                    updatedProduct.getId(), auditEx.getMessage());
        }

        // Actualizar galería de imágenes si se proporcionaron
        if (productDTO.getImages() != null) {
            // Eliminar imágenes anteriores
            productImageRepository.deleteByProductId(updatedProduct.getId());
            // Guardar nuevas imágenes
            if (!productDTO.getImages().isEmpty()) {
                saveProductImages(updatedProduct, productDTO.getImages());
            }
        }

        // NUEVO: Actualizar atributos dinámicos si se proporcionaron
        if (productDTO.getCustomAttributes() != null) {
            logger.info("Actualizando atributos dinámicos...");
            logger.info("Cantidad de atributos nuevos: {}", productDTO.getCustomAttributes().size());

            // Eliminar atributos anteriores
            logger.info("Eliminando atributos anteriores del producto ID: {}", updatedProduct.getId());
            productAttributeRepository.deleteByProductId(updatedProduct.getId());

            // Guardar nuevos atributos
            if (!productDTO.getCustomAttributes().isEmpty()) {
                logger.info("Guardando {} nuevos atributos...", productDTO.getCustomAttributes().size());
                saveProductAttributes(updatedProduct, productDTO.getCustomAttributes());
            }
        } else {
            logger.warn("No se recibieron customAttributes en la actualización");
        }

        // Actualizar contadores de marcas si cambió
        if (oldBrand != null && !oldBrand.equals(product.getBrand())) {
            brandRepository.findById(oldBrand.getId()).ifPresent(b -> {
                b.setProductCount(Math.max(0, b.getProductCount() - 1));
                brandRepository.save(b);
            });
        }
        if (product.getBrand() != null && !product.getBrand().equals(oldBrand)) {
            brandRepository.findById(product.getBrand().getId()).ifPresent(b -> {
                b.setProductCount(b.getProductCount() + 1);
                brandRepository.save(b);
            });
        }

        return convertToDTO(updatedProduct);
    }

    /**
     * Eliminar producto
     */
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Producto no encontrado con ID: " + id));

        // Capturar estado anterior antes de eliminar
        Map<String, Object> oldValues = new HashMap<>();
        oldValues.put("name", product.getName());
        oldValues.put("sku", product.getSku());
        oldValues.put("price", product.getPrice());
        oldValues.put("stock", product.getStock());

        productRepository.delete(product);

        try {
            auditLogService.log(
                    "DELETE", "PRODUCT_DELETE", "PRODUCT",
                    id, oldValues, null, "WARNING", true);
        } catch (Exception auditEx) {
            logger.warn("⚠️ No se pudo registrar audit log para eliminación de producto {}: {}",
                    id, auditEx.getMessage());
        }
    }

    /**
     * Alternar estado activo/inactivo
     */
    public ProductDTO toggleProductStatus(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Producto no encontrado con ID: " + id));

        boolean oldActive = Boolean.TRUE.equals(product.getActive());
        product.setActive(!product.getActive());
        product.setUpdatedAt(LocalDateTime.now());

        Product updatedProduct = productRepository.save(product);

        try {
            Map<String, Object> oldValues = new HashMap<>();
            oldValues.put("active", oldActive);
            Map<String, Object> newValues = new HashMap<>();
            newValues.put("active", updatedProduct.getActive());

            auditLogService.log(
                    "UPDATE", "PRODUCT_STATUS_TOGGLE", "PRODUCT",
                    updatedProduct.getId(), oldValues, newValues, "INFO", true);
        } catch (Exception auditEx) {
            logger.warn("⚠️ No se pudo registrar audit log para toggle de estado de producto {}: {}",
                    id, auditEx.getMessage());
        }

        return convertToDTO(updatedProduct);
    }

    /**
     * Buscar productos por palabra clave
     */
    public List<ProductDTO> searchProducts(String keyword) {
        // searchProducts requiere Pageable, usamos un Page sin límite
        Page<Product> productsPage = productRepository.searchProducts(keyword,
                org.springframework.data.domain.PageRequest.of(0, 1000));
        return productsPage.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 🔍 BÚSQUEDA AVANZADA CON FILTROS Y PAGINACIÓN
     * Busca productos por texto + filtros de marca, categoría y estado activo
     * 
     * @param search     Texto de búsqueda (nombre, SKU, descripción) - puede ser
     *                   null
     * @param brandId    ID de marca para filtrar - puede ser null
     * @param categoryId ID de categoría para filtrar - puede ser null
     * @param active     Estado activo/inactivo - puede ser null
     * @param pageable   Configuración de paginación y ordenamiento
     * @return Página de productos que cumplen los criterios
     */
    public Page<ProductDTO> searchProducts(
            String search,
            Long brandId,
            Long categoryId,
            Boolean active,
            Pageable pageable) {

        logger.info("🔍 === BÚSQUEDA DE PRODUCTOS CON FILTROS ===");
        // search es texto libre del usuario — sanitizar antes de loggear (CWE-117)
        logger.info("📝 Search: {}", search != null ? LogSanitizer.sanitize(search) : "N/A");
        logger.info("🏷️ BrandId: {}", brandId != null ? brandId : "N/A");
        logger.info("📂 CategoryId: {}", categoryId != null ? categoryId : "N/A");
        logger.info("✅ Active: {}", active != null ? active : "N/A");
        logger.info("📄 Page: {}, Size: {}, Sort: {}",
                pageable.getPageNumber(),
                pageable.getPageSize(),
                pageable.getSort());

        Page<Product> productsPage = productRepository.searchProductsWithFilters(
                search, brandId, categoryId, active, pageable);

        logger.info("📊 Resultados encontrados: {} productos (Total: {})",
                productsPage.getNumberOfElements(),
                productsPage.getTotalElements());

        return productsPage.map(this::convertToDTO);
    }

    /**
     * Obtener total de productos
     */
    public long getTotalProductsCount() {
        return productRepository.count();
    }

    /**
     * Obtener productos activos
     */
    public List<ProductDTO> getActiveProducts() {
        List<Product> products = productRepository.findByActiveTrue();
        return products.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Obtener productos por categoría
     */
    public List<ProductDTO> getProductsByCategory(Long categoryId) {
        Page<Product> productsPage = productRepository.findByCategoryIdAndActiveTrue(categoryId,
                org.springframework.data.domain.PageRequest.of(0, 1000));
        return productsPage.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Guardar imágenes adicionales del producto
     */
    private void saveProductImages(Product product, List<ProductImageDTO> imageDTOs) {
        if (imageDTOs == null || imageDTOs.isEmpty()) {
            return;
        }

        for (ProductImageDTO imageDTO : imageDTOs) {
            ProductImage image = new ProductImage();
            image.setProduct(product);
            image.setImageUrl(imageDTO.getImageUrl());
            image.setAltText(imageDTO.getAltText());
            image.setDisplayOrder(imageDTO.getDisplayOrder());
            productImageRepository.save(image);
        }
    }

    /**
     * NUEVO: Guardar atributos dinámicos de un producto
     */
    private void saveProductAttributes(Product product, List<ProductAttributeDTO> attributeDTOs) {
        logger.info(">>> Entrando a saveProductAttributes");
        logger.info(">>> Product ID: {}", product.getId());
        logger.info(">>> Cantidad de atributos a guardar: {}", attributeDTOs.size());

        int order = 0;
        int savedCount = 0;

        for (ProductAttributeDTO attributeDTO : attributeDTOs) {
            // Datos del DTO del request — nivel DEBUG para no exponer en logs de producción
            // (CWE-117)
            logger.debug(">>> Procesando atributo: key='{}', value='{}'",
                    LogSanitizer.sanitize(attributeDTO.getKey()),
                    LogSanitizer.sanitize(attributeDTO.getValue()));

            // Validar que no estén vacíos
            if (attributeDTO.getKey() != null && !attributeDTO.getKey().trim().isEmpty()
                    && attributeDTO.getValue() != null && !attributeDTO.getValue().trim().isEmpty()) {

                ProductAttribute attribute = new ProductAttribute();
                attribute.setProduct(product);
                attribute.setAttributeName(attributeDTO.getKey().trim());
                attribute.setAttributeValue(attributeDTO.getValue().trim());
                int displayOrder = attributeDTO.getDisplayOrder() != null ? attributeDTO.getDisplayOrder() : order++;
                attribute.setDisplayOrder(displayOrder);

                // name y value sanitizados; displayOrder es int primitivo — nivel DEBUG
                // (CWE-117)
                logger.debug(">>> Guardando atributo en BD: name='{}', value='{}', order={}",
                        LogSanitizer.sanitize(attribute.getAttributeName()),
                        LogSanitizer.sanitize(attribute.getAttributeValue()),
                        displayOrder);

                ProductAttribute saved = productAttributeRepository.save(attribute);
                savedCount++;
                // saved.getId() es un Long generado por la BD — se convierte a long primitivo
                // para que SonarQube no lo rastree como dato controlado por el usuario
                // (CWE-117)
                long savedId = saved.getId() != null ? saved.getId().longValue() : -1L;
                logger.debug(">>> Atributo guardado con ID: {}", savedId);
            } else {
                logger.debug(">>> Atributo ignorado (vacío): key='{}', value='{}'",
                        LogSanitizer.sanitize(attributeDTO.getKey()),
                        LogSanitizer.sanitize(attributeDTO.getValue()));
            }
        }

        logger.info(">>> Total de atributos guardados: {}/{}", savedCount, attributeDTOs.size());

        // Verificar en la base de datos
        long count = productAttributeRepository.countByProductId(product.getId());
        logger.info(">>> Verificación en BD - Total de atributos para product_id {}: {}",
                product.getId(), count);
    }

    // =========================================================================
    // SKU GENERATION
    // =========================================================================

    /**
     * Genera un SKU con formato [CATEGORIA]-[MARCA]-[ID].
     * Ejemplos: GUIT-FEND-0042 | ELEC-0018 (sin marca)
     *
     * Reglas:
     * - Prefijo de categoría: primeras 4 letras mayúsculas sin espacios/tildes.
     * - Prefijo de marca: primeras 4 letras mayúsculas (se omite si es null).
     * - ID secuencial: 4 dígitos con ceros a la izquierda.
     */
    private String generateSku(Category category, Brand brand, Long productId) {
        String catPrefix = buildPrefix(category != null ? category.getName() : "PROD", 4);
        String idPart = String.format("%04d", productId);

        if (brand != null) {
            String brandPrefix = buildPrefix(brand.getName(), 4);
            return catPrefix + "-" + brandPrefix + "-" + idPart;
        }
        return catPrefix + "-" + idPart;
    }

    /**
     * Extrae los primeros {@code maxLen} caracteres alfabéticos en mayúsculas,
     * eliminando tildes, espacios y caracteres no ASCII.
     */
    private String buildPrefix(String name, int maxLen) {
        if (name == null || name.isBlank())
            return "PROD";
        String normalized = java.text.Normalizer
                .normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "")
                .replaceAll("[^A-Za-z]", "")
                .toUpperCase();
        return normalized.isEmpty() ? "PROD"
                : normalized.substring(0, Math.min(maxLen, normalized.length()));
    }

    /**
     * Convertir Product a ProductDTO
     */
    private ProductDTO convertToDTO(Product product) {
        ProductDTO dto = new ProductDTO();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setDiscountPrice(product.getDiscountPrice());
        dto.setStock(product.getStock());
        dto.setImageUrl(product.getImageUrl());
        dto.setSku(product.getSku());

        // Null-safe: productos en estado Borrador (importados por CSV) pueden
        // no tener categoría ni marca asignadas aún.
        Category cat = product.getCategory();
        dto.setCategoryId(cat != null ? cat.getId() : null);
        dto.setCategoryName(cat != null ? cat.getName() : null);

        dto.setActive(product.getActive());
        dto.setFeatured(product.getFeatured());

        // Brand como relación (ya era null-safe, se mantiene el patrón)
        Brand brand = product.getBrand();
        if (brand != null) {
            dto.setBrandId(brand.getId());
            dto.setBrandName(brand.getName());
            dto.setBrandLogoUrl(brand.getLogoUrl());
        }

        dto.setModel(product.getModel());
        dto.setWeight(product.getWeight());
        dto.setDimensions(product.getDimensions());
        dto.setViews(product.getViews());
        dto.setSalesCount(product.getSalesCount());

        // NUEVO: Descripción detallada
        dto.setDetailedDescription(product.getDetailedDescription());

        // Convertir galería de imágenes
        if (product.getImages() != null && !product.getImages().isEmpty()) {
            List<ProductImageDTO> imageDTOs = product.getImages().stream()
                    .map(this::convertImageToDTO)
                    .collect(Collectors.toList());
            dto.setImages(imageDTOs);
        }

        // NUEVO: Convertir atributos dinámicos
        if (product.getCustomAttributes() != null && !product.getCustomAttributes().isEmpty()) {
            List<ProductAttributeDTO> attributeDTOs = product.getCustomAttributes().stream()
                    .map(this::convertAttributeToDTO)
                    .collect(Collectors.toList());
            dto.setCustomAttributes(attributeDTOs);
        }

        return dto;
    }

    /**
     * Convertir ProductImage entity a DTO
     */
    private ProductImageDTO convertImageToDTO(ProductImage image) {
        return new ProductImageDTO(
                image.getId(),
                image.getImageUrl(),
                image.getAltText(),
                image.getDisplayOrder());
    }

    /**
     * NUEVO: Convertir ProductAttribute entity a DTO
     */
    private ProductAttributeDTO convertAttributeToDTO(ProductAttribute attribute) {
        return new ProductAttributeDTO(
                attribute.getId(),
                attribute.getAttributeName(),
                attribute.getAttributeValue(),
                attribute.getDisplayOrder());
    }
}
