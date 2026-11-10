package com.security.service;

import com.security.dto.CartDTO;
import com.security.entity.*;
import com.security.exception.*;
import com.security.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service para gestión completa del carrito de compras
 * Soporta usuarios autenticados y sesiones anónimas
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShoppingCartService {

    private final ShoppingCartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final UserRepository userRepository;
    private final WishlistRepository wishlistRepository;
    private final EntityManager entityManager;

    private static final int CART_EXPIRATION_HOURS = 72;
    private static final BigDecimal TAX_RATE = new BigDecimal("0.16"); // 16% IVA

    /**
     * Valida si un item está disponible (activo y con stock suficiente)
     */
    private boolean isItemAvailable(CartItem item) {
        Product p = item.getProduct();
        return p != null && Boolean.TRUE.equals(p.getActive()) 
               && p.getStock() != null && p.getStock() >= item.getQuantity();
    }

    /**
     * Obtiene o crea el carrito activo para un usuario autenticado.
     *
     * <p>Estrategia de búsqueda en orden (FASE 1.2):
     * <ol>
     *   <li>Consulta TODOS los carritos con status='ACTIVE' ignorando expiresAt
     *       (evita que un carrito vencido pase desapercibido y cause un INSERT
     *       que violaría el constraint único {@code ux_shopping_carts_one_active_per_user}).</li>
     *   <li>Si existen, toma el más reciente:
     *       <ul>
     *         <li>Si su expiresAt ya venció → lo <strong>renueva</strong> (+7 días) en lugar
     *             de crear uno nuevo.</li>
     *         <li>Si todavía es válido → lo devuelve directamente.</li>
     *         <li>Si había más de uno → cierra los duplicados como ABANDONED (FASE 1.1).</li>
     *       </ul>
     *   </li>
     *   <li>Si no existe ningún carrito ACTIVE → crea uno nuevo (INSERT seguro).</li>
     * </ol>
     */
    @Transactional
    public ShoppingCart getOrCreateCartForUser(Long userId) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado con ID: " + userId));

        LocalDateTime now = LocalDateTime.now();

        // ── FASE 1.2: consulta SIN filtro de expiresAt para detectar carritos vencidos ──
        List<ShoppingCart> allActiveCarts =
                cartRepository.findAllActiveCartsByUserIdIgnoringExpiry(userId);

        if (!allActiveCarts.isEmpty()) {

            ShoppingCart keptCart = allActiveCarts.get(0); // el más reciente (orden garantizado por query)

            // FASE 1.1: cerrar duplicados si los hubiera
            if (allActiveCarts.size() > 1) {
                int closedCount = 0;
                for (int i = 1; i < allActiveCarts.size(); i++) {
                    ShoppingCart duplicate = allActiveCarts.get(i);
                    duplicate.setStatus("ABANDONED");
                    cartRepository.save(duplicate);
                    closedCount++;
                }
                log.warn(
                    "Multiple active carts detected for userId={}. Keeping cartId={} and closing {} duplicate(s).",
                    userId,
                    keptCart.getId(),
                    closedCount
                );
            }

            // FASE 1.2: si el carrito elegido está vencido, renovar expiresAt en lugar de
            // intentar un INSERT que causaría DataIntegrityViolationException + AssertionFailure.
            if (keptCart.getExpiresAt() != null && keptCart.getExpiresAt().isBefore(now)) {
                LocalDateTime newExpiry = now.plusDays(7);
                keptCart.setExpiresAt(newExpiry);
                keptCart.setUpdatedAt(now);
                cartRepository.save(keptCart);
                log.info(
                    "Carrito vencido renovado: cartId={}, nuevoExpiresAt={}",
                    keptCart.getId(), newExpiry
                );
            } else {
                log.debug("Carrito activo existente encontrado: cartId={}", keptCart.getId());
            }

            return keptCart;
        }

        // ── Sin ningún carrito ACTIVE: crear uno nuevo ──
        ShoppingCart newCart = new ShoppingCart();
        newCart.setUser(user);
        newCart.setStatus("ACTIVE");
        newCart.setExpiresAt(now.plusHours(CART_EXPIRATION_HOURS));
        newCart.setSubtotal(BigDecimal.ZERO);
        newCart.setTax(BigDecimal.ZERO);
        newCart.setTotal(BigDecimal.ZERO);

        ShoppingCart savedCart = cartRepository.save(newCart);
        log.info("Nuevo carrito creado: cartId={}", savedCart.getId());
        return savedCart;
    }

    /**
     * Obtiene o crea un carrito para sesión anónima
     */
    @Transactional
    public ShoppingCart getOrCreateCartForSession(String sessionId) {
        log.info("Obteniendo o creando carrito para sesión: {}", sessionId);

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<ShoppingCart> existingCart = cartRepository.findActiveCartBySessionId(sessionId, now);

        if (existingCart.isPresent()) {
            return existingCart.get();
        }

        // Crear carrito anónimo
        ShoppingCart newCart = new ShoppingCart();
        newCart.setSessionId(sessionId);
        newCart.setStatus("ACTIVE");
        newCart.setExpiresAt(now.plusHours(CART_EXPIRATION_HOURS));
        newCart.setSubtotal(BigDecimal.ZERO);
        newCart.setTax(BigDecimal.ZERO);
        newCart.setTotal(BigDecimal.ZERO);

        return cartRepository.save(newCart);
    }

    /**
     * Obtiene carrito con items cargados
     */
    @Transactional(readOnly = true)
    public ShoppingCart getCartWithItems(Long cartId) {
        return cartRepository.findByIdWithItems(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));
    }

    /**
     * Agrega un producto al carrito
     */
    @Transactional
    public CartDTO.CartResponse addItemToCart(Long cartId, Long productId, Integer quantity) {
        log.info("Agregando producto {} (cantidad: {}) al carrito {}", productId, quantity, cartId);

        // Validaciones
        ShoppingCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        if (!cart.isActive()) {
            throw new CartNotFoundException("El carrito está inactivo o expirado");
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + productId));

        if (!product.getActive()) {
            throw new IllegalArgumentException("El producto no está disponible");
        }

        // Verificar stock
        if (product.getStock() < quantity) {
            throw new InsufficientStockException(productId, quantity, product.getStock());
        }

        // Buscar si el producto ya existe en el carrito
        Optional<CartItem> existingItem = cartItemRepository.findByCartIdAndProductId(cartId, productId);

        if (existingItem.isPresent()) {
            // Actualizar cantidad existente
            CartItem item = existingItem.get();
            Integer newQuantity = item.getQuantity() + quantity;

            if (product.getStock() < newQuantity) {
                throw new InsufficientStockException(productId, newQuantity, product.getStock());
            }

            item.setQuantity(newQuantity);
            item.calculateSubtotal();
            cartItemRepository.save(item);
            log.info("Cantidad actualizada para item {}", item.getId());
        } else {
            // Crear nuevo item
            CartItem newItem = new CartItem();
            newItem.setCart(cart);
            newItem.setProduct(product);
            newItem.setQuantity(quantity);
            newItem.setUnitPriceFromProduct();
            newItem.calculateSubtotal();

            cartItemRepository.save(newItem);
            log.info("Nuevo item agregado al carrito");
        }

        // Recalcular totales del carrito
        recalculateCartTotals(cartId);

        return buildCartResponse(cartId);
    }

    /*
     * Safe wrappers for authenticated flows. These methods derive the user's
     * cart (or validate ownership) from the provided userId so controllers do
     * NOT trust any cartId coming from the client.
     */
    @Transactional
    public CartDTO.CartResponse addItemToUserCart(Long userId, Long productId, Integer quantity) {
        ShoppingCart cart = getOrCreateCartForUser(userId);
        return addItemToCart(cart.getId(), productId, quantity);
    }

    @Transactional
    public CartDTO.CartResponse updateItemQuantityForUser(Long userId, Long itemId, Integer newQuantity) {
        // Ownership check: item must belong to user's active cart
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item no encontrado"));

        Long ownerId = item.getCart().getUser() != null ? item.getCart().getUser().getId() : null;
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new ResourceNotFoundException("Item no encontrado");
        }

        return updateItemQuantity(itemId, newQuantity);
    }

    @Transactional
    public CartDTO.CartResponse removeItemForUser(Long userId, Long itemId) {
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item no encontrado"));

        Long ownerId = item.getCart().getUser() != null ? item.getCart().getUser().getId() : null;
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new ResourceNotFoundException("Item no encontrado");
        }

        return removeItem(itemId);
    }

    @Transactional
    public CartDTO.CartResponse moveItemToWishlistForUser(Long userId, Long itemId) {
        // Ownership check
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item no encontrado"));

        Long ownerId = item.getCart().getUser() != null ? item.getCart().getUser().getId() : null;
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new ResourceNotFoundException("Item no encontrado");
        }

        Product product = item.getProduct();

        // Add to wishlist if not exists
        if (!wishlistRepository.existsByUserIdAndProductId(userId, product.getId())) {
            Wishlist wishlistItem = new Wishlist();
            wishlistItem.setUser(item.getCart().getUser());
            wishlistItem.setProduct(product);
            wishlistItem.setPriority(2); // Medium
            BigDecimal currentPrice = product.getDiscountPrice() != null ? product.getDiscountPrice() : product.getPrice();
            wishlistItem.setPriceWhenAdded(currentPrice);
            wishlistRepository.save(wishlistItem);
        }

        // Delete from cart
        Long cartId = item.getCart().getId();
        cartItemRepository.delete(item);

        // Recalculate totals
        recalculateCartTotals(cartId);

        return buildCartResponse(cartId);
    }

    @Transactional
    public CartDTO.CouponAppliedResponse applyCouponForUser(Long userId, String couponCode) {
        ShoppingCart cart = getOrCreateCartForUser(userId);
        return applyCoupon(cart.getId(), couponCode);
    }

    @Transactional
    public CartDTO.CartResponse removeCouponForUser(Long userId) {
        ShoppingCart cart = getOrCreateCartForUser(userId);
        return removeCoupon(cart.getId());
    }

    @Transactional
    public void clearCartForUser(Long userId) {
        ShoppingCart cart = getOrCreateCartForUser(userId);
        clearCart(cart.getId());
    }

    @Transactional(readOnly = true)
    public CartDTO.CartSummaryResponse getCartSummaryForUser(Long userId) {
        return cartRepository.findAllActiveCartsByUserIdIgnoringExpiry(userId).stream().findFirst()
                .filter(ShoppingCart::isActive)
                .map(cart -> getCartSummary(cart.getId()))
                .orElseGet(() -> CartDTO.CartSummaryResponse.builder()
                        .totalItems(0).subtotal(BigDecimal.ZERO).total(BigDecimal.ZERO)
                        .canCheckout(false).unavailableItemsCount(0).build());
    }

    @Transactional(readOnly = true)
    public CartDTO.CartValidationResponse validateCartForUser(Long userId) {
        return cartRepository.findAllActiveCartsByUserIdIgnoringExpiry(userId).stream().findFirst()
                .filter(ShoppingCart::isActive)
                .map(cart -> validateCartForCheckout(cart.getId()))
                .orElseGet(() -> CartDTO.CartValidationResponse.builder()
                        .globalErrors(List.of("El carrito está vacío")).itemWarnings(List.of()).build());
    }

    /**
     * Actualiza la cantidad de un item
     */
    @Transactional
    public CartDTO.CartResponse updateItemQuantity(Long itemId, Integer newQuantity) {
        log.info("Actualizando cantidad del item {} a {}", itemId, newQuantity);

        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item no encontrado con ID: " + itemId));

        Product product = item.getProduct();

        // Verificar stock y disponibilidad
        if (product == null || !Boolean.TRUE.equals(product.getActive()) || product.getStock() == null || product.getStock() == 0) {
            throw new IllegalStateException("El producto está agotado o inactivo. Elimínalo o muévelo a tu lista de deseos.");
        }

        if (product.getStock() < newQuantity) {
            throw new InsufficientStockException(product.getId(), newQuantity, product.getStock());
        }

        item.setQuantity(newQuantity);
        item.calculateSubtotal();
        cartItemRepository.save(item);

        // Recalcular totales
        Long cartId = item.getCart().getId();
        recalculateCartTotals(cartId);

        return buildCartResponse(cartId);
    }

    /**
     * Elimina un item del carrito
     */
    @Transactional
    public CartDTO.CartResponse removeItem(Long itemId) {
        log.info("Eliminando item {}", itemId);

        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item no encontrado con ID: " + itemId));

        Long cartId = item.getCart().getId();
        cartItemRepository.delete(item);

        // Recalcular totales
        recalculateCartTotals(cartId);

        return buildCartResponse(cartId);
    }

    /**
     * Aplica un cupón al carrito usando stored procedure
     */
    @Transactional
    public CartDTO.CouponAppliedResponse applyCoupon(Long cartId, String couponCode) {
        log.info("Aplicando cupón {} al carrito {}", couponCode, cartId);

        ShoppingCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        Coupon coupon = couponRepository.findByCodeIgnoreCase(couponCode)
                .orElseThrow(() -> new InvalidCouponException(couponCode, "Cupón no encontrado"));

        // Validar cupón
        if (!coupon.isValid()) {
            throw new InvalidCouponException(couponCode, "Cupón no válido o expirado");
        }

        // Verificar si el usuario ya usó este cupón
        if (cart.getUser() != null && coupon.getUsageLimitPerUser() != null) {
            Long usageCount = couponUsageRepository.countByCouponIdAndUserId(
                    coupon.getId(), cart.getUser().getId());

            if (usageCount >= coupon.getUsageLimitPerUser()) {
                throw new InvalidCouponException(couponCode,
                        "Has alcanzado el límite de uso de este cupón");
            }
        }

        // Verificar monto mínimo
        if (coupon.getMinimumPurchase() != null &&
                cart.getSubtotal().compareTo(coupon.getMinimumPurchase()) < 0) {
            throw new InvalidCouponException(couponCode,
                    String.format("Compra mínima requerida: $%.2f", coupon.getMinimumPurchase()));
        }

        // Llamar al stored procedure para aplicar el cupón
        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_apply_coupon_to_cart");
            query.registerStoredProcedureParameter("p_cart_id", Long.class, ParameterMode.IN);
            query.registerStoredProcedureParameter("p_coupon_id", Long.class, ParameterMode.IN);

            query.setParameter("p_cart_id", cartId);
            query.setParameter("p_coupon_id", coupon.getId());

            query.execute();

            // Refrescar el carrito
            entityManager.refresh(cart);

            log.info("Cupón {} aplicado exitosamente al carrito {}", couponCode, cartId);

            return CartDTO.CouponAppliedResponse.builder()
                    .couponCode(couponCode)
                    .discountType(coupon.getDiscountType())
                    .discountValue(coupon.getDiscountValue())
                    .discountApplied(cart.getDiscount())
                    .newTotal(cart.getTotal())
                    .message("Cupón aplicado exitosamente")
                    .build();

        } catch (Exception e) {
            log.error("Error al aplicar cupón: {}", e.getMessage());
            throw new InvalidCouponException(couponCode, "Error al aplicar cupón: " + e.getMessage());
        }
    }

    /**
     * Remueve el cupón del carrito
     */
    @Transactional
    public CartDTO.CartResponse removeCoupon(Long cartId) {
        log.info("Removiendo cupón del carrito {}", cartId);

        ShoppingCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        cart.setCouponCode(null);
        cart.setDiscount(BigDecimal.ZERO);
        cartRepository.save(cart);

        recalculateCartTotals(cartId);

        return buildCartResponse(cartId);
    }

    /**
     * Vacía el carrito
     */
    @Transactional
    public void clearCart(Long cartId) {
        log.info("Vaciando carrito {}", cartId);

        ShoppingCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        cartItemRepository.deleteByCartId(cartId);

        cart.setSubtotal(BigDecimal.ZERO);
        cart.setTax(BigDecimal.ZERO);
        cart.setDiscount(BigDecimal.ZERO);
        cart.setTotal(BigDecimal.ZERO);
        cart.setCouponCode(null);

        cartRepository.save(cart);
    }

    /**
     * Transfiere carrito de sesión anónima a usuario autenticado
     */
    @Transactional
    public ShoppingCart transferCartToUser(String sessionId, Long userId) {
        log.info("Transfiriendo carrito de sesión {} a usuario {}", sessionId, userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        LocalDateTime now = LocalDateTime.now();

        // Buscar carrito anónimo
        Optional<ShoppingCart> anonymousCart = cartRepository.findActiveCartBySessionId(sessionId, now);
        if (anonymousCart.isEmpty()) {
            log.info("No hay carrito anónimo para transferir");
            return getOrCreateCartForUser(userId);
        }

        // Buscar carrito existente del usuario usando el método tolerante a duplicados.
        // getOrCreateCartForUser ya cierra eventuales duplicados si los hubiera.
        // FASE 1.1: no usamos findActiveCartByUserId directamente aquí.
        ShoppingCart targetCart = getOrCreateCartForUser(userId);

        ShoppingCart sourceCart = anonymousCart.get();

        // Evitar merge consigo mismo si la sesión ya está asignada al usuario
        if (sourceCart.getId().equals(targetCart.getId())) {
            log.info("El carrito de sesión {} ya pertenece al usuario {}", sessionId, userId);
            return targetCart;
        }

        List<CartItem> itemsToTransfer = cartItemRepository.findByCartId(sourceCart.getId());

        for (CartItem item : itemsToTransfer) {
            // Verificar si el producto ya existe en el carrito del usuario
            Optional<CartItem> existingItem = cartItemRepository.findByCartIdAndProductId(
                    targetCart.getId(), item.getProduct().getId());

            if (existingItem.isPresent()) {
                // Sumar cantidades
                CartItem existing = existingItem.get();
                existing.setQuantity(existing.getQuantity() + item.getQuantity());
                existing.calculateSubtotal();
                cartItemRepository.save(existing);
            } else {
                // Mover item al carrito del usuario
                item.setCart(targetCart);
                cartItemRepository.save(item);
            }
        }

        // Marcar carrito anónimo como CONVERTED y recalcular totales destino
        sourceCart.setStatus("CONVERTED");
        cartRepository.save(sourceCart);

        recalculateCartTotals(targetCart.getId());

        return targetCart;
    }

    /**
     * Recalcula totales del carrito (llamado después de cambios)
     */
    private void recalculateCartTotals(Long cartId) {
        ShoppingCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        List<CartItem> items = cartItemRepository.findByCartId(cartId);

        // Calcular subtotal (solo items disponibles)
        BigDecimal subtotal = items.stream()
                .filter(this::isItemAvailable)
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        cart.setSubtotal(subtotal);

        // Calcular IVA
        BigDecimal tax = subtotal.multiply(TAX_RATE);
        cart.setTax(tax);

        // Aplicar descuento si existe cupón
        BigDecimal discount = cart.getDiscount() != null ? cart.getDiscount() : BigDecimal.ZERO;

        // Calcular total (solo items disponibles)
        BigDecimal total = subtotal.add(tax).subtract(discount);
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            total = BigDecimal.ZERO;
        }
        cart.setTotal(total);

        cartRepository.save(cart);
        log.debug("Totales recalculados para carrito {}: Subtotal={}, Tax={}, Discount={}, Total={}",
                cartId, subtotal, tax, discount, total);
    }

    /**
     * Construye response completo del carrito
     */
    @Transactional(readOnly = true)
    public CartDTO.CartResponse buildCartResponse(Long cartId) {
        ShoppingCart cart = cartRepository.findByIdWithItems(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        List<CartDTO.CartItemResponse> itemResponses = cart.getItems().stream()
                .map(this::buildCartItemResponse)
                .collect(Collectors.toList());

        boolean canCheckout = itemResponses.stream().allMatch(CartDTO.CartItemResponse::getAvailable);
        String warningMessage = null;
        if (!canCheckout && !itemResponses.isEmpty()) {
            warningMessage = "El carrito contiene productos no disponibles. Por favor, elimínalos o muévelos a tu lista de deseos para poder comprar.";
        }

        BigDecimal unavailableItemsTotal = cart.getItems().stream()
                .filter(item -> !isItemAvailable(item))
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return CartDTO.CartResponse.builder()
                .cartId(cart.getId())
                .userId(cart.getUser() != null ? cart.getUser().getId() : null)
                .sessionId(cart.getSessionId())
                .items(itemResponses)
                .totalItems(itemResponses.stream()
                        .filter(CartDTO.CartItemResponse::getAvailable)
                        .mapToInt(CartDTO.CartItemResponse::getQuantity).sum())
                .subtotal(cart.getSubtotal())
                .tax(cart.getTax())
                .taxRate(TAX_RATE.multiply(new BigDecimal("100"))) // Convertir a porcentaje
                .discount(cart.getDiscount())
                .total(cart.getTotal())
                .couponCode(cart.getCouponCode())
                .status(cart.getStatus())
                .canCheckout(canCheckout && !itemResponses.isEmpty())
                .warningMessage(warningMessage)
                .unavailableItemsTotal(unavailableItemsTotal)
                .expiresAt(cart.getExpiresAt())
                .createdAt(cart.getCreatedAt())
                .updatedAt(cart.getUpdatedAt())
                .build();
    }

    /**
     * Construye response de item individual
     */
    private CartDTO.CartItemResponse buildCartItemResponse(CartItem item) {
        Product product = item.getProduct();
        
        boolean isAvailable = isItemAvailable(item);
        String availabilityStatus = "AVAILABLE";
        String warningMessage = null;

        if (product == null || !Boolean.TRUE.equals(product.getActive())) {
            availabilityStatus = "INACTIVE";
            warningMessage = "El producto ya no está disponible.";
        } else if (product.getStock() == null || product.getStock() == 0) {
            availabilityStatus = "OUT_OF_STOCK";
            warningMessage = "El producto está agotado.";
        } else if (product.getStock() < item.getQuantity()) {
            availabilityStatus = "INSUFFICIENT_STOCK";
            warningMessage = String.format("Solo hay %d unidades disponibles.", product.getStock());
        }

        return CartDTO.CartItemResponse.builder()
                .itemId(item.getId())
                .productId(product.getId())
                .productName(product.getName())
                .productImage(product.getImageUrl())
                .productSku(product.getSku())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .subtotal(item.getSubtotal())
                .availableStock(product.getStock())
                .available(isAvailable)
                .availabilityStatus(availabilityStatus)
                .warningMessage(warningMessage)
                .addedAt(item.getAddedAt())
                .build();
    }

    /**
     * Obtiene resumen del carrito
     */
    @Transactional(readOnly = true)
    public CartDTO.CartSummaryResponse getCartSummary(Long cartId) {
        ShoppingCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        Integer totalItems = cartItemRepository.sumQuantityByCartId(cartId);
        
        List<CartItem> items = cartItemRepository.findByCartId(cartId);
        boolean canCheckout = items.stream().allMatch(this::isItemAvailable);
        long unavailableItemsCount = items.stream().filter(item -> !isItemAvailable(item)).count();
        String warningMessage = null;
        if (!canCheckout && !items.isEmpty()) {
            warningMessage = "El carrito contiene productos no disponibles. Por favor, elimínalos o muévelos a tu lista de deseos para poder comprar.";
        }

        return CartDTO.CartSummaryResponse.builder()
                .cartId(cart.getId())
                .totalItems(totalItems != null ? totalItems : 0)
                .subtotal(cart.getSubtotal())
                .total(cart.getTotal())
                .status(cart.getStatus())
                .canCheckout(canCheckout && !items.isEmpty())
                .unavailableItemsCount((int) unavailableItemsCount)
                .warningMessage(warningMessage)
                .build();
    }

    /**
     * Valida el carrito antes de checkout
     */
    @Transactional(readOnly = true)
    public CartDTO.CartValidationResponse validateCartForCheckout(Long cartId) {
        List<String> globalErrors = new ArrayList<>();
        List<CartDTO.CartItemValidation> itemWarnings = new ArrayList<>();

        ShoppingCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        if (!cart.isActive()) {
            globalErrors.add("El carrito no está activo");
        }

        List<CartItem> items = cartItemRepository.findByCartId(cartId);

        if (items.isEmpty()) {
            globalErrors.add("El carrito está vacío");
        }

        // Validar stock de cada item
        for (CartItem item : items) {
            Product product = item.getProduct();
            
            String availabilityStatus = "AVAILABLE";
            String warningMessage = null;

            if (product == null || !Boolean.TRUE.equals(product.getActive())) {
                availabilityStatus = "INACTIVE";
                warningMessage = "El producto ya no está disponible.";
            } else if (product.getStock() == null || product.getStock() == 0) {
                availabilityStatus = "OUT_OF_STOCK";
                warningMessage = "El producto está agotado.";
            } else if (product.getStock() < item.getQuantity()) {
                availabilityStatus = "INSUFFICIENT_STOCK";
                warningMessage = String.format("Solo hay %d unidades disponibles. Solicitado: %d", product.getStock(), item.getQuantity());
            }

            if (!"AVAILABLE".equals(availabilityStatus)) {
                itemWarnings.add(CartDTO.CartItemValidation.builder()
                        .itemId(item.getId())
                        .productId(product != null ? product.getId() : null)
                        .availabilityStatus(availabilityStatus)
                        .warningMessage(warningMessage)
                        .build());
            }
        }

        boolean canCheckout = globalErrors.isEmpty() && itemWarnings.isEmpty();

        return CartDTO.CartValidationResponse.builder()
                .valid(canCheckout)
                .canCheckout(canCheckout)
                .globalErrors(globalErrors)
                .itemWarnings(itemWarnings)
                .build();
    }
}
