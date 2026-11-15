package com.security.dto;

import com.security.enums.OrderStatus;
import com.security.enums.PaymentMethod;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 📦 DTO para transferencia de datos de Órdenes
 * 
 * Incluye toda la información necesaria para el frontend:
 * - Datos básicos de la orden
 * - Información del cliente
 * - Items de la orden
 * - Estados (orden, pago, envío)
 * - Direcciones
 * - Fechas y notas
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderDTO {

    // ==================== DATOS BÁSICOS ====================

    private Long id;
    private String orderNumber;
    private LocalDateTime orderDate;

    // ==================== CLIENTE ====================

    private Long userId;
    private String customerName; // firstName + lastName
    private String customerEmail;
    private String customerPhone;

    // ==================== TOTALES ====================

    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal shipping;
    private BigDecimal discount;
    private BigDecimal total;

    // ==================== ESTADOS ====================

    private OrderStatus status;
    private String statusDisplayName;

    private ShippingStatus shippingStatus;
    private String shippingStatusDisplayName;

    private PaymentStatus paymentStatus;
    private String paymentStatusDisplayName;

    // ==================== PAGO ====================

    private PaymentMethod paymentMethod;
    private String transactionId;

    // ==================== COMPROBANTE PAGO ====================

    private Boolean hasPaymentProof;
    private String paymentProofStatus;
    private LocalDateTime paymentProofUploadedAt;
    private String paymentProofRejectionReason;

    // ==================== ENVÍO ====================

    private String shippingAddress;
    private String billingAddress;
    private String trackingNumber;
    private LocalDateTime shippedAt;
    private LocalDateTime deliveredAt;

    private String deliveryType; // "PICKUP_STORE", "LOCAL_DELIVERY", "EXTERNAL_SHIPPING_QUOTE"
    private String shippingMethod;
    private String shippingLabel;

    // Campos de Recolección en Tienda (Pickup)
    private String pickupStatus;
    private String pickupCodeLast4;
    private Boolean pickupCodeAvailable;
    private LocalDateTime pickupReadyAt;
    private LocalDateTime pickedUpAt;
    private String pickedUpByName;
    private Long pickedUpByAuthorizationId;
    
    // Sólo para el cliente (desencriptado, expuesto condicionalmente en un endpoint separado o si aplica)
    private String pickupCode;
    
    private List<AuthorizedPersonDTO> authorizedPersons = new ArrayList<>();

    // ==================== ITEMS ====================

    private List<OrderItemDTO> items = new ArrayList<>();
    private Integer totalItems; // Total de items en la orden

    // ==================== NOTAS ====================

    private String notes;
    private String customerNotes;
    private String cancellationReason;
    private Long cancelledBy;
    private String cancelSource;

    // ==================== FECHAS ====================

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime cancelledAt;

    // ==================== MÉTODOS AUXILIARES ====================

    /**
     * Calcula el total de items en la orden
     */
    public Integer calculateTotalItems() {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return items.stream()
                .mapToInt(OrderItemDTO::getQuantity)
                .sum();
    }

    /**
     * Verifica si la orden puede ser cancelada
     */
    public boolean canBeCancelled() {
        return status == OrderStatus.PENDING ||
                status == OrderStatus.CONFIRMED;
    }

    /**
     * Verifica si la orden puede ser reembolsada
     */
    public boolean canBeRefunded() {
        return paymentStatus == PaymentStatus.PAID &&
                (status == OrderStatus.COMPLETED || status == OrderStatus.CANCELLED);
    }

    /**
     * Verifica si la orden puede cambiar de estado de envío
     */
    public boolean canUpdateShipping() {
        return paymentStatus == PaymentStatus.PAID &&
                shippingStatus != ShippingStatus.DELIVERED &&
                shippingStatus != ShippingStatus.RETURNED;
    }
}
