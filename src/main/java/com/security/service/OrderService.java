package com.security.service;

import com.security.dto.OrderDTO;
import com.security.dto.OrderItemDTO;
import com.security.entity.Order;
import com.security.entity.OrderItem;
import com.security.entity.User;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import com.security.repository.OrderRepository;
import com.security.service.AuditLogService;
import com.security.dto.CheckoutRequest;
import com.security.entity.CartItem;
import com.security.entity.Product;
import com.security.entity.ShoppingCart;
import com.security.repository.ProductRepository;
import com.security.repository.ShoppingCartRepository;
import com.security.repository.UserRepository;
import com.security.service.ShoppingCartService;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servicio de Órdenes — Lógica de negocio completa.
 *
 * Funcionalidades:
 * - CRUD de órdenes
 * - Búsqueda avanzada con filtros combinados
 * - Cambio de estados con validaciones de transición
 * - Conversión de entidades a DTOs
 * - Logs de auditoría sin datos sensibles
 */
@Service
@Transactional
@Slf4j
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStateTransitionService stateTransitionService;

    @Autowired
    private com.security.repository.PaymentProofRepository paymentProofRepository;
    
    @Autowired
    private PaymentProofService paymentProofService;

    @Autowired
    private OrderTimelineEventService timelineEventService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ShoppingCartService shoppingCartService;

    @Autowired
    private ShoppingCartRepository cartRepository;

    @Autowired
    private DeliveryMethodService deliveryMethodService;

    @Autowired
    private com.security.repository.OrderPickupAuthorizationRepository orderPickupAuthorizationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderPickupService orderPickupService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    // ==================== LISTAR Y BUSCAR ====================

    /**
     * Obtener todas las órdenes con paginación.
     */
    public Page<OrderDTO> getAllOrders(Pageable pageable) {
        log.info("getAllOrders — página: {}, tamaño: {}", pageable.getPageNumber(), pageable.getPageSize());
        Page<Order> ordersPage = orderRepository.findAll(pageable);
        log.info("getAllOrders — {} de {} órdenes encontradas", ordersPage.getNumberOfElements(),
                ordersPage.getTotalElements());
        return ordersPage.map(this::convertToDTO);
    }

    /**
     * Búsqueda avanzada con filtros múltiples combinados.
     *
     * Usa JpaSpecificationExecutor para construir los predicados de forma
     * dinámica en Java. Esto evita el error "bytea → timestamp" que ocurre
     * en Hibernate 6.3 + PostgreSQL cuando se pasa null como LocalDateTime
     * en un parámetro JPQL con CAST.
     *
     * Cada filtro sólo se añade al predicado si el valor está presente (no null),
     * por lo que nunca se envía un parámetro null de tipo temporal al JDBC.
     */
    public Page<OrderDTO> searchOrders(
            String search,
            OrderStatus orderStatus,
            PaymentStatus paymentStatus,
            ShippingStatus shippingStatus,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable) {

        log.info("searchOrders — search={}, orderStatus={}, paymentStatus={}, shippingStatus={}, page={}, size={}",
                search != null ? "[provided]" : "null", orderStatus, paymentStatus, shippingStatus,
                pageable.getPageNumber(), pageable.getPageSize());

        Specification<Order> spec = buildSearchSpecification(
                search, orderStatus, paymentStatus, shippingStatus, startDate, endDate);

        Page<Order> ordersPage = orderRepository.findAll(spec, pageable);

        log.info("searchOrders — {} de {} resultados", ordersPage.getNumberOfElements(), ordersPage.getTotalElements());
        return ordersPage.map(this::convertToDTO);
    }

    /**
     * Construye la Specification de búsqueda con predicados condicionales.
     * Cada predicado sólo se añade si el parámetro correspondiente es no-null.
     */
    private Specification<Order> buildSearchSpecification(
            String search,
            OrderStatus orderStatus,
            PaymentStatus paymentStatus,
            ShippingStatus shippingStatus,
            LocalDateTime startDate,
            LocalDateTime endDate) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Búsqueda de texto: número de orden, nombre/apellido/email del usuario
            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                Join<Order, User> user = root.join("user", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("orderNumber")), pattern),
                        cb.like(cb.lower(user.get("firstName")), pattern),
                        cb.like(cb.lower(user.get("lastName")), pattern),
                        cb.like(cb.lower(user.get("email")), pattern)));
            }

            // Estado de la orden
            if (orderStatus != null) {
                predicates.add(cb.equal(root.get("status"), orderStatus));
            }

            // Estado de pago
            if (paymentStatus != null) {
                predicates.add(cb.equal(root.get("paymentStatus"), paymentStatus));
            }

            // Estado de envío
            if (shippingStatus != null) {
                predicates.add(cb.equal(root.get("shippingStatus"), shippingStatus));
            }

            // Fecha desde — sólo se añade si el valor no es null (sin CAST, sin JDBC null)
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }

            // Fecha hasta — ídem
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }

            // Evitar JOIN duplicado en count query
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.distinct(true);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Obtener órdenes de un cliente específico.
     */
    public Page<OrderDTO> getOrdersByUserId(Long userId, Pageable pageable) {
        log.info("getOrdersByUserId — userId={}", userId);

        Page<Order> ordersPage = orderRepository.findByUserId(userId, pageable);

        log.info("getOrdersByUserId — {} órdenes encontradas para usuario {}", ordersPage.getTotalElements(), userId);
        return ordersPage.map(this::convertToDTO);
    }

    /**
     * Obtener una orden por su ID.
     */
    public OrderDTO getOrderById(Long id) {
        log.info("getOrderById — id={}", id);
        Order order = getOrderEntityById(id);
        log.info("getOrderById — orden encontrada: {}", order.getOrderNumber());
        return convertToDTO(order);
    }
    
    /**
     * Obtener entidad de orden (Uso interno)
     */
    public Order getOrderEntityById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("getOrderEntityById — orden no encontrada con id={}", id);
                    return new RuntimeException("Orden no encontrada con ID: " + id);
                });
    }

    /**
     * Obtener orden por número de orden.
     */
    public OrderDTO getOrderByNumber(String orderNumber) {
        log.info("getOrderByNumber — orderNumber={}", orderNumber);
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> {
                    log.warn("getOrderByNumber — no encontrada: {}", orderNumber);
                    return new RuntimeException("Orden no encontrada: " + orderNumber);
                });
        return convertToDTO(order);
    }

    // ==================== CHECKOUT Y CANCELACIÓN ====================

    /**
     * FASE 4: Convierte un carrito en una orden.
     * Valida disponibilidad, resta stock, crea la orden y cierra el carrito.
     */
    public OrderDTO createOrderFromCart(Long userId, CheckoutRequest request) {
        log.info("createOrderFromCart — userId={}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con ID: " + userId));

        ShoppingCart cart = shoppingCartService.getOrCreateCartForUser(userId);

        var validation = shoppingCartService.validateCartForCheckout(cart.getId());
        if (!validation.isValid()) {
            log.warn("createOrderFromCart — checkout fallido por carrito inválido. Errores: {}", validation.getGlobalErrors());
            throw new RuntimeException("El carrito no es válido para checkout. Revise la disponibilidad de los productos.");
        }

        // Crear la orden
        Order order = new Order();
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setUser(user);
        
        // Datos de checkout
        order.setShippingAddress(request.getShippingAddress());
        order.setBillingAddress(request.getBillingAddress());
        order.setPaymentMethod(request.getPaymentMethod());
        order.setCustomerNotes(request.getNotes());
        
        // Totales del carrito
        order.setSubtotal(cart.getSubtotal());
        order.setTax(cart.getTax());
        order.setDiscount(cart.getDiscount() != null ? cart.getDiscount() : java.math.BigDecimal.ZERO);
        
        // Cálculo de envío según el método seleccionado
        com.security.dto.ShippingCalculationResult deliveryResult = deliveryMethodService.calculateDelivery(
                request.getDeliveryOption(), request.getState(), request.getCity(), request.getPostalCode()
        );

        if (deliveryResult.isRequiresQuote()) {
            throw new RuntimeException("Tu envío requiere cotización antes de confirmar la compra.");
        }
        if (!deliveryResult.isCanProceedToPayment()) {
            throw new RuntimeException(deliveryResult.getMessage());
        }

        if ("PICKUP_STORE".equals(deliveryResult.getDeliveryMethod())) {
            List<com.security.dto.AuthorizedPersonDTO> authPersons = request.getAuthorizedPersons();
            List<String> errors = new java.util.ArrayList<>();
            
            if (authPersons == null || authPersons.isEmpty()) {
                errors.add("Debes registrar al menos una persona autorizada.");
            } else if (authPersons.size() > 3) {
                errors.add("Solo puedes registrar hasta 3 personas autorizadas.");
            } else {
                java.util.Set<String> normalizedNames = new java.util.HashSet<>();
                for (int i = 0; i < authPersons.size(); i++) {
                    com.security.dto.AuthorizedPersonDTO ap = authPersons.get(i);
                    String prefix = "Persona " + (i + 1) + ": ";
                    
                    if (ap.getFullName() == null || ap.getFullName().trim().length() < 5 || ap.getFullName().trim().length() > 80) {
                        errors.add(prefix + "Ingresa un nombre válido de entre 5 y 80 caracteres.");
                    } else {
                        String cleanName = ap.getFullName().trim();
                        if (!cleanName.matches("^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ\\s]+$")) {
                            errors.add(prefix + "El nombre solo puede contener letras y espacios.");
                        } else {
                            String normalized = cleanName.toLowerCase()
                                    .replaceAll("[áàäâã]", "a")
                                    .replaceAll("[éèëê]", "e")
                                    .replaceAll("[íìïî]", "i")
                                    .replaceAll("[óòöôõ]", "o")
                                    .replaceAll("[úùüû]", "u")
                                    .replaceAll("\\s+", " ");
                            if (!normalizedNames.add(normalized)) {
                                errors.add(prefix + "Esta persona ya está autorizada.");
                            }
                            ap.setFullName(cleanName);
                        }
                    }
                    
                    if (ap.getPhone() != null && !ap.getPhone().trim().isEmpty()) {
                        String phone = ap.getPhone().trim();
                        if (!phone.matches("^\\d{10}$")) {
                            errors.add(prefix + "El teléfono debe tener exactamente 10 dígitos y solo contener números.");
                        }
                        ap.setPhone(phone);
                    }
                    
                    if (ap.getRelationship() != null && !ap.getRelationship().trim().isEmpty()) {
                        String rel = ap.getRelationship().trim();
                        if (rel.length() > 50 || !rel.matches("^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ\\s]+$")) {
                            errors.add(prefix + "La referencia no debe superar 50 caracteres y solo debe contener letras y espacios.");
                        }
                        ap.setRelationship(rel);
                    }
                }
            }
            
            if (!errors.isEmpty()) {
                throw new com.security.exception.PickupValidationException("Revisa las personas autorizadas para recoger el pedido.", errors);
            }
        }

        order.setDeliveryType(deliveryResult.getDeliveryMethod());
        order.setShippingMethod(deliveryResult.getDeliveryMethod());
        order.setShippingLabel(deliveryResult.getDeliveryLabel());
        order.setShipping(deliveryResult.getShippingCost() != null ? deliveryResult.getShippingCost() : java.math.BigDecimal.ZERO);
        
        if ("PICKUP_STORE".equals(deliveryResult.getDeliveryMethod())) {
            order.setPickupStatus(com.security.enums.PickupStatus.WAITING_PAYMENT);
            order.setShippingAddress(null);
            // El backend usa la config como snapshot
            order.setPickupAddressSnapshot(deliveryResult.getDeliveryLabel()); 
        } else {
            order.setPickupStatus(com.security.enums.PickupStatus.NOT_APPLICABLE);
        }
        
        // Total final sumando envío
        java.math.BigDecimal total = cart.getTotal().add(order.getShipping());
        order.setTotal(total);
        
        // Estados iniciales
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setShippingStatus(ShippingStatus.PENDING);
        
        // Procesar items y restar stock
        for (CartItem cartItem : cart.getItems()) {
            Product product = cartItem.getProduct();
            
            // Restar stock
            if (product.getStock() < cartItem.getQuantity()) {
                throw new RuntimeException("Stock insuficiente para el producto: " + product.getName());
            }
            product.setStock(product.getStock() - cartItem.getQuantity());
            productRepository.save(product);
            
            // Crear snapshot en OrderItem
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(product);
            orderItem.setProductName(product.getName());
            orderItem.setProductSku(product.getSku());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setUnitPrice(cartItem.getUnitPrice());
            orderItem.setSubtotal(cartItem.getSubtotal());
            
            order.getItems().add(orderItem);
        }
        
        // Guardar orden
        Order savedOrder = orderRepository.save(order);
        
        // Procesar personas autorizadas para Pickup
        if ("PICKUP_STORE".equals(deliveryResult.getDeliveryMethod()) && request.getAuthorizedPersons() != null) {
            for (com.security.dto.AuthorizedPersonDTO apDTO : request.getAuthorizedPersons()) {
                com.security.entity.OrderPickupAuthorization auth = new com.security.entity.OrderPickupAuthorization();
                auth.setOrder(savedOrder);
                auth.setFullName(apDTO.getFullName());
                auth.setPhone(apDTO.getPhone());
                auth.setRelationship(apDTO.getRelationship());
                auth.setIsPrimary(apDTO.isPrimary());
                orderPickupAuthorizationRepository.save(auth);
            }
            try {
                timelineEventService.recordEvent(
                    savedOrder, "PICKUP_AUTHORIZED", "Recolección autorizada", "Se registraron personas autorizadas para recoger el pedido.",
                    null, OrderStatus.PENDING, null, PaymentStatus.PENDING, null, ShippingStatus.PENDING,
                    user, "CUSTOMER", "ORDER_SERVICE", "BOTH", null
                );
            } catch (Exception e) {}
        }
        
        // Registrar evento de creación
        try {
            timelineEventService.recordEvent(
                savedOrder,
                "ORDER_CREATED",
                "Orden Creada",
                "La orden ha sido creada exitosamente.",
                null,
                OrderStatus.PENDING,
                null,
                PaymentStatus.PENDING,
                null,
                ShippingStatus.PENDING,
                user,
                "CUSTOMER",
                "ORDER_SERVICE",
                "BOTH",
                null
            );
        } catch (Exception e) {
            log.warn("No se pudo registrar evento de timeline ORDER_CREATED: {}", e.getMessage());
        }
        
        // Marcar carrito como convertido
        cart.setStatus("CONVERTED");
        cartRepository.save(cart);
        
        log.info("createOrderFromCart — Orden creada exitosamente: {} para usuario {}", savedOrder.getOrderNumber(), userId);
        
        return convertToDTO(savedOrder);
    }

    /**
     * FASE 4: Cancelación centralizada y segura de una orden.
     * Restaura el stock de los productos.
     */
    public OrderDTO cancelOrder(Long orderId, String reason, String source, Long actorId) {
        log.info("cancelOrder — orderId={}, source={}, actorId={}", orderId, source, actorId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Orden no encontrada"));

        // Prevenir doble cancelación o cancelar órdenes ya entregadas
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("La orden ya se encuentra cancelada.");
        }
        if (order.getShippingStatus() == ShippingStatus.DELIVERED) {
            throw new IllegalStateException("No se puede cancelar una orden que ya fue entregada.");
        }

        // Reglas de cancelación según el origen
        if ("CUSTOMER".equals(source)) {
            if (order.getUser() == null || !order.getUser().getId().equals(actorId)) {
                throw new IllegalStateException("No tiene permiso para cancelar esta orden.");
            }
            if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.CONFIRMED) {
                throw new IllegalStateException("Solo puede cancelar órdenes en estado PENDING o CONFIRMED.");
            }
        } else if ("ADMIN".equals(source)) {
            if (order.getShippingStatus() == ShippingStatus.SHIPPED || order.getShippingStatus() == ShippingStatus.DELIVERED) {
                throw new IllegalStateException("El pedido ya fue enviado o entregado; inicie un flujo de devolución o incidencia en su lugar.");
            }
        } else if ("SYSTEM".equals(source) || "SYSTEM_EXPIRATION".equals(source)) {
            if (order.getStatus() != OrderStatus.PENDING) {
                log.warn("Se intentó expirar una orden que no está PENDING (ID: {})", orderId);
            }
        } else {
            throw new IllegalArgumentException("Origen de cancelación desconocido.");
        }

        // Restaurar stock
        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            if (product != null) {
                product.setStock((product.getStock() != null ? product.getStock() : 0) + item.getQuantity());
                productRepository.save(product);
                log.info("cancelOrder — stock restaurado para producto {}: +{}", product.getId(), item.getQuantity());
            }
        }

        // Actualizar estados
        OrderStatus oldStatus = order.getStatus();
        PaymentStatus oldPaymentStatus = order.getPaymentStatus();
        
        String finalSource = "SYSTEM_EXPIRATION".equals(source) ? "SYSTEM" : source;
        
        order.setStatus(OrderStatus.CANCELLED);
        if ("SYSTEM_EXPIRATION".equals(source) && oldPaymentStatus == PaymentStatus.PENDING) {
            order.setPaymentStatus(PaymentStatus.FAILED);
        }
        
        order.setCancelledAt(LocalDateTime.now());
        order.setCancellationReason(reason);
        order.setCancelSource(finalSource);
        order.setCancelledBy(actorId);

        Map<String, Object> oldValues = new HashMap<>();
        oldValues.put("status", oldStatus.name());

        Order savedOrder;
        try {
            savedOrder = orderRepository.saveAndFlush(order);
        } catch (Exception e) {
            log.error("Error al persistir cancelación de la orden {}: {}", orderId, e.getMessage());
            Map<String, Object> failNewValues = new HashMap<>();
            failNewValues.put("status", OrderStatus.CANCELLED.name());
            failNewValues.put("cancelSource", finalSource);
            failNewValues.put("cancellationReason", reason);
            failNewValues.put("error", "Error de persistencia al cancelar");

            auditLogService.log(
                    "ORDER_CANCELLED", "ORDER_STATUS_CHANGE", "ORDER",
                    orderId, oldValues, failNewValues, "ERROR", false);
            throw e;
        }
        
        // Rechazar comprobantes pendientes
        paymentProofService.rejectPendingProofsForCancelledOrder(savedOrder.getId(), "Orden cancelada: " + reason, actorId);

        // Sincronización con pagos: cancelar pagos activos
        paymentService.cancelActivePaymentsForOrder(
                savedOrder.getId(),
                "Pago cancelado automáticamente porque la orden fue cancelada.",
                source,
                actorId
        );

        // Auditoría y Timeline
        Map<String, Object> newValues = new HashMap<>();
        newValues.put("status", OrderStatus.CANCELLED.name());
        newValues.put("cancelSource", finalSource);
        newValues.put("cancellationReason", reason);

                
        User actorUser = null;
        if (actorId != null) {
            actorUser = userRepository.findById(actorId).orElse(null);
        }
        
        String timelineEvt = "SYSTEM_EXPIRATION".equals(source) ? "ORDER_EXPIRED" : "ORDER_CANCELLED";
        String timelineTitle = "SYSTEM_EXPIRATION".equals(source) ? "Orden Expirada" : "Orden Cancelada";

        String actorType = "CUSTOMER";
        if ("ADMIN".equals(source)) {
            actorType = "ADMIN";
        } else if ("SYSTEM".equals(source) || "SYSTEM_EXPIRATION".equals(source)) {
            actorType = "SYSTEM";
        }

        timelineEventService.recordEvent(
                savedOrder,
                timelineEvt,
                timelineTitle,
                "Motivo: " + reason,
                oldStatus,
                OrderStatus.CANCELLED,
                oldPaymentStatus,
                savedOrder.getPaymentStatus(),
                savedOrder.getShippingStatus(),
                savedOrder.getShippingStatus(),
                actorUser,
                actorType,
                "ORDER_SERVICE",
                "BOTH",
                null
        );

        applicationEventPublisher.publishEvent(new com.security.event.AuditSuccessEvent(
                this, "ORDER_CANCELLED", "ORDER_STATUS_CHANGE", "ORDER",
                orderId, oldValues, newValues, "INFO"));

        return convertToDTO(savedOrder);
    }

    // ==================== ACTUALIZAR ESTADOS ====================

    /**
     * Actualizar el estado de la orden.
     */
    public OrderDTO updateOrderStatus(Long orderId, OrderStatus newStatus) {
        log.info("updateOrderStatus — orderId={}, newStatus={}", orderId, newStatus);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada"));

        OrderStatus oldStatus = order.getStatus();
        
        if (oldStatus == newStatus) {
            return convertToDTO(order);
        }

        // Usar servicio de transición de estados
        stateTransitionService.validateOrderStatusTransition(order, newStatus);

        order.setStatus(newStatus);

        if (newStatus == OrderStatus.CANCELLED) {
            order.setCancelledAt(LocalDateTime.now());
        }

        Order savedOrder = orderRepository.save(order);
        log.info("updateOrderStatus — estado actualizado de {} a {} para orden {}", oldStatus, newStatus, orderId);

        // Sincronización con pagos: cancelar pagos activos si la orden fue cancelada
        if (newStatus == OrderStatus.CANCELLED) {
            try {
                paymentService.cancelActivePaymentsForOrder(
                        savedOrder.getId(),
                        "Pago cancelado automáticamente porque la orden fue cancelada.",
                        "ADMIN_STATUS_UPDATE",
                        null
                );
            } catch (Exception e) {
                log.error("No se pudieron cancelar pagos activos asociados a la orden cancelada. orderId={}", savedOrder.getId(), e);
            }
        }

        // Auditoría de cambio de estado
        try {
            Map<String, Object> oldValues = new HashMap<>();
            oldValues.put("status", oldStatus != null ? oldStatus.name() : null);
            Map<String, Object> newValues = new HashMap<>();
            newValues.put("status", newStatus.name());

            auditLogService.log(
                    "ORDER_STATUS_CHANGE", "ORDER_STATUS_CHANGE", "ORDER",
                    orderId, oldValues, newValues, "INFO", true);
                    
            timelineEventService.recordEvent(
                    savedOrder,
                    "ORDER_STATUS_CHANGED",
                    "Estado de la orden actualizado",
                    "El estado de la orden cambió de " + oldStatus + " a " + newStatus,
                    oldStatus,
                    newStatus,
                    savedOrder.getPaymentStatus(),
                    savedOrder.getPaymentStatus(),
                    savedOrder.getShippingStatus(),
                    savedOrder.getShippingStatus(),
                    null, // actorUser no disponible aquí en firma original
                    "ADMIN",
                    "ORDER_SERVICE",
                    "BOTH",
                    null
            );
        } catch (Exception auditEx) {
            log.warn("⚠️ No se pudo registrar evento de timeline o audit log para cambio de estado de orden {}: {}",
                    orderId, auditEx.getMessage());
        }

        return convertToDTO(savedOrder);
    }

    /**
     * Actualizar el estado de pago de la orden.
     * Si se marca como PAID y la orden está PENDING, se confirma automáticamente.
     */
    public OrderDTO updatePaymentStatus(Long orderId, PaymentStatus newStatus) {
        log.info("updatePaymentStatus — orderId={}, newStatus={}", orderId, newStatus);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada"));

        PaymentStatus oldStatus = order.getPaymentStatus();
        order.setPaymentStatus(newStatus);

        if (newStatus == PaymentStatus.PAID && order.getStatus() == OrderStatus.PENDING) {
            order.setStatus(OrderStatus.CONFIRMED);
            log.info("updatePaymentStatus — orden {} confirmada automáticamente al recibir pago", orderId);
        }

        // Si es pago confirmado y es pickup, generar código
        if (newStatus == PaymentStatus.PAID && "PICKUP_STORE".equals(order.getDeliveryType())) {
            orderPickupService.generatePickupCode(order);
            // order ya fue guardado dentro de generatePickupCode, pero lo volveremos a guardar abajo
        }

        Order savedOrder = orderRepository.save(order);
        log.info("updatePaymentStatus — estado de pago actualizado de {} a {} para orden {}", oldStatus, newStatus,
                orderId);

        // Auditoría de cambio de estado de pago
        try {
            Map<String, Object> oldValues = new HashMap<>();
            oldValues.put("paymentStatus", oldStatus != null ? oldStatus.name() : null);
            Map<String, Object> newValues = new HashMap<>();
            newValues.put("paymentStatus", newStatus.name());

            auditLogService.log(
                    "ORDER_PAYMENT_CHANGE", "ORDER_STATUS_CHANGE", "ORDER",
                    orderId, oldValues, newValues, "INFO", true);
        } catch (Exception auditEx) {
            log.warn("⚠️ No se pudo registrar audit log para cambio de pago de orden {}: {}",
                    orderId, auditEx.getMessage());
        }

        return convertToDTO(savedOrder);
    }

    public String getPlainPickupCodeForUser(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada"));
                
        if (!order.getUser().getId().equals(userId)) {
            return null;
        }
        
        return orderPickupService.getPlainCodeForCustomer(order);
    }

    /**
     * Actualizar el estado de envío de la orden.
     * Valida que el pago esté confirmado antes de marcar como SHIPPED.
     */
    public OrderDTO updateShippingStatus(Long orderId, ShippingStatus newShippingStatus, String trackingNumber) {
        log.info("updateShippingStatus — orderId={}, newStatus={}", orderId, newShippingStatus);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada"));

        ShippingStatus oldShippingStatus = order.getShippingStatus();
        OrderStatus oldOrderStatus = order.getStatus();
        
        if (oldShippingStatus == newShippingStatus && 
            ((trackingNumber == null && order.getTrackingNumber() == null) || 
             (trackingNumber != null && trackingNumber.equals(order.getTrackingNumber())))) {
            return convertToDTO(order);
        }

        // Validar transición de estado de envío usando la máquina de estados estricta
        if (oldShippingStatus != newShippingStatus) {
            stateTransitionService.validateShippingStatusTransition(order, newShippingStatus);
        }

        order.setShippingStatus(newShippingStatus);

        if (trackingNumber != null && !trackingNumber.trim().isEmpty()) {
            order.setTrackingNumber(trackingNumber.trim());
        }

        if (newShippingStatus == ShippingStatus.SHIPPED) {
            order.setShippedAt(LocalDateTime.now());
        } else if (newShippingStatus == ShippingStatus.DELIVERED) {
            order.setDeliveredAt(LocalDateTime.now());
            if (order.getStatus() != OrderStatus.COMPLETED) {
                order.setStatus(OrderStatus.COMPLETED);
                log.info("updateShippingStatus — orden {} marcada como completada al entregar", orderId);
            }
        }

        Order savedOrder = orderRepository.save(order);
        log.info("updateShippingStatus — estado de envío actualizado de {} a {} para orden {}", oldShippingStatus, newShippingStatus,
                orderId);

        // Auditoría de cambio de estado de envío
        try {
            Map<String, Object> oldValues = new HashMap<>();
            oldValues.put("shippingStatus", oldShippingStatus != null ? oldShippingStatus.name() : null);
            Map<String, Object> newValues = new HashMap<>();
            newValues.put("shippingStatus", newShippingStatus.name());
            if (trackingNumber != null && !trackingNumber.trim().isEmpty()) {
                newValues.put("trackingNumber", trackingNumber.trim());
            }

            auditLogService.log(
                    "ORDER_SHIPPING_CHANGE", "ORDER_STATUS_CHANGE", "ORDER",
                    orderId, oldValues, newValues, "INFO", true);
                    
            timelineEventService.recordEvent(
                    savedOrder,
                    "SHIPPING_STATUS_CHANGED",
                    "Estado de envío actualizado",
                    "El estado de envío cambió de " + oldShippingStatus + " a " + newShippingStatus,
                    oldOrderStatus,
                    savedOrder.getStatus(),
                    savedOrder.getPaymentStatus(),
                    savedOrder.getPaymentStatus(),
                    oldShippingStatus,
                    newShippingStatus,
                    null, // actorUser no disponible aquí en firma original
                    "ADMIN",
                    "SHIPPING",
                    "BOTH",
                    null
            );
        } catch (Exception auditEx) {
            log.warn("⚠️ No se pudo registrar audit log para cambio de envío de orden {}: {}",
                    orderId, auditEx.getMessage());
        }

        return convertToDTO(savedOrder);
    }

    // ==================== ESTADÍSTICAS ====================

    /**
     * Obtener contadores de órdenes por cada estado.
     */
    public OrderStatsDTO getOrderStats() {
        log.info("getOrderStats — calculando estadísticas de órdenes");
        OrderStatsDTO stats = new OrderStatsDTO();
        stats.setTotalOrders(orderRepository.count());
        stats.setPendingOrders(orderRepository.countByStatus(OrderStatus.PENDING));
        stats.setConfirmedOrders(orderRepository.countByStatus(OrderStatus.CONFIRMED));
        stats.setProcessingOrders(orderRepository.countByStatus(OrderStatus.PROCESSING));
        stats.setCompletedOrders(orderRepository.countByStatus(OrderStatus.COMPLETED));
        stats.setCancelledOrders(orderRepository.countByStatus(OrderStatus.CANCELLED));
        log.info("getOrderStats — {} órdenes totales", stats.getTotalOrders());
        return stats;
    }

    // ==================== CONVERSIÓN DTO ====================

    /**
     * Convierte una entidad Order a OrderDTO.
     */
    private OrderDTO convertToDTO(Order order) {
        OrderDTO dto = new OrderDTO();

        // Datos básicos
        dto.setId(order.getId());
        dto.setOrderNumber(order.getOrderNumber());
        dto.setOrderDate(order.getCreatedAt());

        // Cliente
        User user = order.getUser();
        if (user != null) {
            dto.setUserId(user.getId());
            dto.setCustomerName(user.getFirstName() + " " + user.getLastName());
            dto.setCustomerEmail(user.getEmail());
            dto.setCustomerPhone(user.getPhone());
        }

        // Totales
        dto.setSubtotal(order.getSubtotal());
        dto.setTax(order.getTax());
        dto.setShipping(order.getShipping());
        dto.setDiscount(order.getDiscount());
        dto.setTotal(order.getTotal());

        // Estados
        dto.setStatus(order.getStatus());
        dto.setStatusDisplayName(order.getStatus().name());

        dto.setShippingStatus(order.getShippingStatus());
        dto.setShippingStatusDisplayName(order.getShippingStatus().getDisplayName());

        dto.setPaymentStatus(order.getPaymentStatus());
        dto.setPaymentStatusDisplayName(order.getPaymentStatus().name());

        // Pago
        dto.setPaymentMethod(order.getPaymentMethod());
        dto.setTransactionId(order.getTransactionId());

        // Comprobante
        com.security.entity.PaymentProof proof = paymentProofRepository.findTopByOrderIdOrderByCreatedAtDesc(order.getId()).orElse(null);
        if (proof != null) {
            dto.setHasPaymentProof(true);
            dto.setPaymentProofStatus(proof.getStatus().name());
            dto.setPaymentProofUploadedAt(proof.getCreatedAt());
            dto.setPaymentProofRejectionReason(proof.getRejectionReason());
        } else {
            dto.setHasPaymentProof(false);
        }

        // Envío
        dto.setShippingAddress(order.getShippingAddress());
        dto.setBillingAddress(order.getBillingAddress());
        dto.setTrackingNumber(order.getTrackingNumber());
        dto.setShippedAt(order.getShippedAt());
        dto.setDeliveredAt(order.getDeliveredAt());
        
        dto.setDeliveryType(order.getDeliveryType());
        dto.setPickupStatus(order.getPickupStatus() != null ? order.getPickupStatus().name() : null);
        dto.setPickupCodeLast4(order.getPickupCodeLast4());
        dto.setPickupCodeAvailable(order.getPickupCodeEncrypted() != null);
        dto.setPickupReadyAt(order.getPickupReadyAt());
        dto.setPickedUpAt(order.getPickedUpAt());
        dto.setPickedUpByName(order.getPickedUpByName());
        dto.setPickedUpByAuthorizationId(order.getPickedUpByAuthorizationId());
        
        List<com.security.entity.OrderPickupAuthorization> auths = orderPickupAuthorizationRepository.findByOrderId(order.getId());
        if (auths != null && !auths.isEmpty()) {
            List<com.security.dto.AuthorizedPersonDTO> authDtos = auths.stream().map(ap -> {
                com.security.dto.AuthorizedPersonDTO apDto = new com.security.dto.AuthorizedPersonDTO();
                apDto.setId(ap.getId());
                apDto.setFullName(ap.getFullName());
                apDto.setPhone(ap.getPhone());
                apDto.setRelationship(ap.getRelationship());
                apDto.setPrimary(ap.getIsPrimary() != null ? ap.getIsPrimary() : false);
                return apDto;
            }).toList();
            dto.setAuthorizedPersons(authDtos);
        }

        // Items
        List<OrderItemDTO> itemDTOs = order.getItems().stream()
                .map(this::convertItemToDTO)
                .collect(Collectors.toList());
        dto.setItems(itemDTOs);
        dto.setTotalItems(dto.calculateTotalItems());

        // Notas
        dto.setNotes(order.getNotes());
        dto.setCustomerNotes(order.getCustomerNotes());
        dto.setCancellationReason(order.getCancellationReason());
        dto.setCancelledBy(order.getCancelledBy());
        dto.setCancelSource(order.getCancelSource());

        // Fechas
        dto.setCreatedAt(order.getCreatedAt());
        dto.setUpdatedAt(order.getUpdatedAt());
        dto.setCancelledAt(order.getCancelledAt());

        return dto;
    }

    /**
     * Convierte una entidad OrderItem a OrderItemDTO.
     */
    private OrderItemDTO convertItemToDTO(OrderItem item) {
        OrderItemDTO dto = new OrderItemDTO();
        dto.setId(item.getId());
        dto.setProductId(item.getProduct().getId());
        dto.setProductName(item.getProductName()); // Usamos el nombre guardado
        dto.setProductSku(item.getProductSku()); // Usamos el SKU guardado
        dto.setProductImage(item.getProduct().getImageUrl());
        dto.setQuantity(item.getQuantity());
        dto.setPrice(item.getUnitPrice()); // ✅ CORREGIDO: unitPrice no price
        dto.setSubtotal(item.getSubtotal());
        return dto;
    }

    // ==================== DTO AUXILIARES ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderStatsDTO {
        private Long totalOrders;
        private Long pendingOrders;
        private Long confirmedOrders;
        private Long processingOrders;
        private Long completedOrders;
        private Long cancelledOrders;
    }
}
