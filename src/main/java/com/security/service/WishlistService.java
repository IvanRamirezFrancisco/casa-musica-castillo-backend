package com.security.service;

import com.security.dto.WishlistDTO;
import com.security.entity.Product;
import com.security.entity.User;
import com.security.entity.Wishlist;
import com.security.exception.DuplicateActionException;
import com.security.exception.ResourceNotFoundException;
import com.security.exception.UnauthorizedException;
import com.security.repository.ProductRepository;
import com.security.repository.UserRepository;
import com.security.repository.WishlistRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service para gestión de wishlist (lista de deseos)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final ShoppingCartService shoppingCartService;

    /**
     * Agrega un producto a la wishlist
     */
    @Transactional
    public WishlistDTO.WishlistItemResponse addToWishlist(Long userId, WishlistDTO.AddToWishlistRequest request) {
        log.info("Usuario {} agregando producto {} a wishlist", userId, request.getProductId());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado"));

        // Verificar si ya está en wishlist
        if (wishlistRepository.existsByUserIdAndProductId(userId, request.getProductId())) {
            throw new DuplicateActionException("Este producto ya está en tu wishlist");
        }

        Wishlist wishlistItem = new Wishlist();
        wishlistItem.setUser(user);
        wishlistItem.setProduct(product);
        wishlistItem.setPriority(request.getPriority() != null ? request.getPriority() : 2); // MEDIUM
        wishlistItem.setNotes(request.getNotes());

        // El precio se establece automáticamente por trigger
        // Pero lo establecemos también en Java por si acaso
        BigDecimal currentPrice = product.getDiscountPrice() != null
                ? product.getDiscountPrice()
                : product.getPrice();
        wishlistItem.setPriceWhenAdded(currentPrice);

        Wishlist savedItem = wishlistRepository.save(wishlistItem);

        log.info("Producto agregado a wishlist: ID {}", savedItem.getId());
        return buildWishlistItemResponse(savedItem);
    }

    /**
     * Remueve un item de la wishlist
     */
    @Transactional
    public void removeFromWishlist(Long wishlistId, Long userId) {
        log.info("Usuario {} eliminando item {} de wishlist", userId, wishlistId);

        Wishlist item = wishlistRepository.findById(wishlistId)
                .orElseThrow(() -> new ResourceNotFoundException("Item no encontrado en wishlist"));

        if (!item.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("No tienes permiso para eliminar este item");
        }

        wishlistRepository.delete(item);
        log.info("Item {} eliminado de wishlist", wishlistId);
    }

    /**
     * Actualiza un item de la wishlist
     */
    @Transactional
    public WishlistDTO.WishlistItemResponse updateWishlistItem(
            Long wishlistId, Long userId, WishlistDTO.UpdateWishlistRequest request) {

        log.info("Usuario {} actualizando item {} de wishlist", userId, wishlistId);

        Wishlist item = wishlistRepository.findById(wishlistId)
                .orElseThrow(() -> new ResourceNotFoundException("Item no encontrado en wishlist"));

        if (!item.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("No tienes permiso para actualizar este item");
        }

        if (request.getPriority() != null) {
            item.setPriority(request.getPriority());
        }

        if (request.getNotes() != null) {
            item.setNotes(request.getNotes());
        }

        Wishlist updatedItem = wishlistRepository.save(item);

        return buildWishlistItemResponse(updatedItem);
    }

    /**
     * Obtiene la wishlist completa de un usuario
     */
    @Transactional(readOnly = true)
    public WishlistDTO.WishlistResponse getWishlist(Long userId) {
        log.info("Obteniendo wishlist del usuario {}", userId);

        List<Wishlist> items = wishlistRepository.findByUserIdWithProduct(userId);

        List<WishlistDTO.WishlistItemResponse> itemResponses = items.stream()
                .map(this::buildWishlistItemResponse)
                .collect(Collectors.toList());

        int highPriorityCount = (int) items.stream().filter(i -> i.getPriority() == 3).count();
        int outOfStockCount = (int) items.stream().filter(i -> i.getProduct().getStock() == 0).count();
        int priceDroppedCount = (int) items.stream().filter(Wishlist::isPriceDropped).count();

        return WishlistDTO.WishlistResponse.builder()
                .items(itemResponses)
                .totalItems(items.size())
                .highPriorityItems(highPriorityCount)
                .outOfStockItems(outOfStockCount)
                .priceDroppedItems(priceDroppedCount)
                .build();
    }

    /**
     * Obtiene items con bajada de precio
     */
    @Transactional(readOnly = true)
    public List<WishlistDTO.WishlistItemResponse> getPriceDroppedItems(Long userId) {
        List<Wishlist> items = wishlistRepository.findPriceDroppedByUserId(userId);

        return items.stream()
                .map(this::buildWishlistItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene items fuera de stock
     */
    @Transactional(readOnly = true)
    public List<WishlistDTO.WishlistItemResponse> getOutOfStockItems(Long userId) {
        List<Wishlist> items = wishlistRepository.findOutOfStockByUserId(userId);

        return items.stream()
                .map(this::buildWishlistItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene items en stock
     */
    @Transactional(readOnly = true)
    public List<WishlistDTO.WishlistItemResponse> getInStockItems(Long userId) {
        List<Wishlist> items = wishlistRepository.findInStockByUserId(userId);

        return items.stream()
                .map(this::buildWishlistItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Mueve un item de wishlist al carrito usando stored procedure
     */
    @Transactional
    public void moveToCart(Long wishlistId, Long userId) {
        log.info("Usuario {} moviendo item {} a carrito", userId, wishlistId);

        Wishlist item = wishlistRepository.findById(wishlistId)
                .orElseThrow(() -> new ResourceNotFoundException("Item no encontrado en wishlist"));

        if (!item.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("No tienes permiso para mover este item");
        }

        Product product = item.getProduct();
        
        if (product == null || !Boolean.TRUE.equals(product.getActive()) || product.getStock() == null || product.getStock() <= 0) {
            throw new IllegalStateException("El producto no está disponible para mover al carrito");
        }

        // Agregar al carrito usando ShoppingCartService
        shoppingCartService.addItemToUserCart(userId, product.getId(), 1);

        // Eliminar de wishlist
        wishlistRepository.delete(item);
        
        log.info("Item {} movido a carrito y eliminado de wishlist", wishlistId);
    }

    /**
     * Verifica productos con descuentos usando stored procedure
     */
    @Transactional
    public List<WishlistDTO.WishlistNotification> checkPriceDrops(Long userId) {
        log.info("Verificando descuentos en wishlist del usuario {}", userId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_check_wishlist_discounts");
            query.registerStoredProcedureParameter("p_user_id", Long.class, ParameterMode.IN);
            query.setParameter("p_user_id", userId);

            query.execute();

            // Obtener items con bajada de precio
            List<Wishlist> priceDroppedItems = wishlistRepository.findPriceDroppedByUserId(userId);

            return priceDroppedItems.stream()
                    .map(item -> WishlistDTO.WishlistNotification.builder()
                            .wishlistId(item.getId())
                            .productId(item.getProduct().getId())
                            .productName(item.getProduct().getName())
                            .notificationType("PRICE_DROP")
                            .message(String.format("¡%s tiene un nuevo precio!", item.getProduct().getName()))
                            .currentPrice(item.getProduct().getDiscountPrice() != null
                                    ? item.getProduct().getDiscountPrice()
                                    : item.getProduct().getPrice())
                            .previousPrice(item.getPriceWhenAdded())
                            .discountPercentage(item.getDiscountPercentage().doubleValue())
                            .notifiedAt(item.getUpdatedAt())
                            .build())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error al verificar descuentos: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Verifica productos de vuelta en stock usando stored procedure
     */
    @Transactional
    public List<WishlistDTO.WishlistNotification> checkBackInStock(Long userId) {
        log.info("Verificando productos en stock en wishlist del usuario {}", userId);

        try {
            StoredProcedureQuery query = entityManager
                    .createStoredProcedureQuery("sales.sp_check_wishlist_back_in_stock");
            query.registerStoredProcedureParameter("p_user_id", Long.class, ParameterMode.IN);
            query.setParameter("p_user_id", userId);

            query.execute();

            // Obtener items no notificados que están en stock
            List<Wishlist> backInStockItems = wishlistRepository.findUnnotifiedBackInStock(userId);

            return backInStockItems.stream()
                    .map(item -> WishlistDTO.WishlistNotification.builder()
                            .wishlistId(item.getId())
                            .productId(item.getProduct().getId())
                            .productName(item.getProduct().getName())
                            .notificationType("BACK_IN_STOCK")
                            .message(String.format("¡%s está de vuelta en stock!", item.getProduct().getName()))
                            .currentPrice(item.getProduct().getDiscountPrice() != null
                                    ? item.getProduct().getDiscountPrice()
                                    : item.getProduct().getPrice())
                            .notifiedAt(item.getUpdatedAt())
                            .build())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error al verificar stock: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Obtiene resumen de la wishlist
     */
    @Transactional(readOnly = true)
    public WishlistDTO.WishlistSummaryResponse getWishlistSummary(Long userId) {
        List<Wishlist> items = wishlistRepository.findByUserIdWithProduct(userId);

        int highPriority = (int) items.stream().filter(i -> i.getPriority() == 3).count();
        int mediumPriority = (int) items.stream().filter(i -> i.getPriority() == 2).count();
        int lowPriority = (int) items.stream().filter(i -> i.getPriority() == 1).count();

        int inStock = (int) items.stream().filter(i -> i.getProduct().getStock() > 0).count();
        int outOfStock = (int) items.stream().filter(i -> i.getProduct().getStock() == 0).count();

        int priceDropped = (int) items.stream().filter(Wishlist::isPriceDropped).count();

        BigDecimal totalValue = wishlistRepository.calculateTotalValue(userId);
        BigDecimal potentialSavings = wishlistRepository.calculatePotentialSavings(userId);

        return WishlistDTO.WishlistSummaryResponse.builder()
                .totalItems(items.size())
                .highPriorityCount(highPriority)
                .mediumPriorityCount(mediumPriority)
                .lowPriorityCount(lowPriority)
                .inStockCount(inStock)
                .outOfStockCount(outOfStock)
                .priceDroppedCount(priceDropped)
                .totalValue(totalValue)
                .potentialSavings(potentialSavings)
                .build();
    }

    /**
     * Construye response de item de wishlist
     */
    private WishlistDTO.WishlistItemResponse buildWishlistItemResponse(Wishlist item) {
        Product product = item.getProduct();

        BigDecimal currentPrice = product.getDiscountPrice() != null
                ? product.getDiscountPrice()
                : product.getPrice();

        BigDecimal priceDifference = item.getPriceWhenAdded() != null
                ? item.getPriceWhenAdded().subtract(currentPrice)
                : BigDecimal.ZERO;

        String priorityLabel = switch (item.getPriority()) {
            case 3 -> "HIGH";
            case 2 -> "MEDIUM";
            case 1 -> "LOW";
            default -> "MEDIUM";
        };

        boolean isAvailable = product != null && Boolean.TRUE.equals(product.getActive()) 
                              && product.getStock() != null && product.getStock() > 0;
        String availabilityStatus = "AVAILABLE";
        if (product == null || !Boolean.TRUE.equals(product.getActive())) {
            availabilityStatus = "INACTIVE";
        } else if (product.getStock() == null || product.getStock() == 0) {
            availabilityStatus = "OUT_OF_STOCK";
        }

        return WishlistDTO.WishlistItemResponse.builder()
                .wishlistId(item.getId())
                .productId(product.getId())
                .productName(product.getName())
                .productImage(product.getImageUrl())
                .productSku(product.getSku())
                .currentPrice(currentPrice)
                .priceWhenAdded(item.getPriceWhenAdded())
                .priceDifference(priceDifference)
                .discountPercentage(item.isPriceDropped() ? item.getDiscountPercentage().doubleValue() : 0.0)
                .priceDropped(item.isPriceDropped())
                .availableStock(product.getStock())
                .inStock(product.getStock() > 0)
                .available(isAvailable)
                .availabilityStatus(availabilityStatus)
                .canMoveToCart(isAvailable)
                .priority(item.getPriority())
                .priorityLabel(priorityLabel)
                .notes(item.getNotes())
                .notifiedBackInStock(item.getNotifiedBackInStock())
                .notifiedDiscount(item.getNotifiedDiscount())
                .addedAt(item.getAddedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }

    // ── FASE 2: métodos para el toggle del botón corazón ──────────────────────

    /**
     * Verifica si un producto está en la wishlist del usuario.
     * Devuelve respuesta ligera con el wishlistId para que el frontend
     * pueda realizar el DELETE directamente sin cargar la lista completa.
     */
    @Transactional(readOnly = true)
    public WishlistDTO.CheckResponse checkProductInWishlist(Long userId, Long productId) {
        return wishlistRepository.findByUserIdAndProductId(userId, productId)
                .map(item -> WishlistDTO.CheckResponse.builder()
                        .inWishlist(true)
                        .wishlistId(item.getId())
                        .build())
                .orElseGet(() -> WishlistDTO.CheckResponse.builder()
                        .inWishlist(false)
                        .wishlistId(null)
                        .build());
    }

    /**
     * Elimina un item de la wishlist por productId, sin necesitar el wishlistId.
     * Valida que el item pertenezca al usuario antes de borrar.
     *
     * @throws ResourceNotFoundException si el producto no está en la wishlist del usuario.
     */
    @Transactional
    public void removeByProductId(Long userId, Long productId) {
        log.info("Usuario {} eliminando producto {} de wishlist por productId", userId, productId);

        // Primero verificamos que el item existe y pertenece al usuario
        wishlistRepository.findByUserIdAndProductId(userId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El producto no está en tu wishlist"));

        wishlistRepository.deleteByUserIdAndProductId(userId, productId);
        log.info("Producto {} eliminado de wishlist del usuario {}", productId, userId);
    }
}
