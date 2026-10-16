package com.security.service;

import com.security.entity.Order;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class OrderStateTransitionService {

    public List<OrderStatus> getAllowedOrderStatuses(Order order) {
        List<OrderStatus> allowed = new ArrayList<>();
        OrderStatus current = order.getStatus();
        PaymentStatus payment = order.getPaymentStatus();
        ShippingStatus shipping = order.getShippingStatus();

        allowed.add(current); // El estado actual siempre debe estar

        if (current == OrderStatus.COMPLETED || current == OrderStatus.CANCELLED) {
            return allowed; // Estados finales, no se permiten más cambios
        }

        if (payment != PaymentStatus.PAID) {
            return allowed;
        }

        // Si el pago es PAID:
        switch (current) {
            case PENDING:
                if (!allowed.contains(OrderStatus.CONFIRMED)) allowed.add(OrderStatus.CONFIRMED);
                break;
            case CONFIRMED:
                if (!allowed.contains(OrderStatus.PROCESSING)) allowed.add(OrderStatus.PROCESSING);
                break;
            case PROCESSING:
                if (shipping == ShippingStatus.DELIVERED) {
                    if (!allowed.contains(OrderStatus.COMPLETED)) allowed.add(OrderStatus.COMPLETED);
                }
                break;
            default:
                break;
        }

        return allowed;
    }

    public List<ShippingStatus> getAllowedShippingStatuses(Order order) {
        List<ShippingStatus> allowed = new ArrayList<>();
        ShippingStatus current = order.getShippingStatus();
        PaymentStatus payment = order.getPaymentStatus();
        OrderStatus orderStatus = order.getStatus();

        allowed.add(current);

        if (orderStatus == OrderStatus.CANCELLED || current == ShippingStatus.DELIVERED || current == ShippingStatus.RETURNED || orderStatus == OrderStatus.COMPLETED) {
            return allowed;
        }

        if (payment != PaymentStatus.PAID) {
            return allowed; // Solo PENDING o el estado actual
        }

        // Si el pago es PAID y la orden no está cancelada/completada
        switch (current) {
            case PENDING:
                if (!allowed.contains(ShippingStatus.PREPARING)) allowed.add(ShippingStatus.PREPARING);
                break;
            case PREPARING:
                if (!allowed.contains(ShippingStatus.SHIPPED)) allowed.add(ShippingStatus.SHIPPED);
                break;
            case SHIPPED:
                if (!allowed.contains(ShippingStatus.IN_TRANSIT)) allowed.add(ShippingStatus.IN_TRANSIT);
                if (!allowed.contains(ShippingStatus.DELIVERED)) allowed.add(ShippingStatus.DELIVERED); // Si el negocio permite saltar
                break;
            case IN_TRANSIT:
                if (!allowed.contains(ShippingStatus.DELIVERED)) allowed.add(ShippingStatus.DELIVERED);
                break;
            case DELIVERED:
                if (!allowed.contains(ShippingStatus.RETURNED)) allowed.add(ShippingStatus.RETURNED);
                break;
            default:
                break;
        }

        return allowed;
    }

    public void validateOrderStatusTransition(Order order, OrderStatus newStatus) {
        if (order.getStatus() == newStatus) return;

        List<OrderStatus> allowed = getAllowedOrderStatuses(order);
        if (!allowed.contains(newStatus)) {
            if (order.getPaymentStatus() != PaymentStatus.PAID) {
                throw new IllegalStateException("Aprueba el pago antes de confirmar o avanzar esta orden.");
            }
            if (newStatus == OrderStatus.COMPLETED && order.getShippingStatus() != ShippingStatus.DELIVERED) {
                throw new IllegalStateException("No se puede completar la orden hasta que el envío esté entregado.");
            }
            throw new IllegalStateException("Transición de estado de orden inválida de " + order.getStatus() + " a " + newStatus);
        }
    }

    public void validateShippingStatusTransition(Order order, ShippingStatus newStatus) {
        if (order.getShippingStatus() == newStatus) return;

        List<ShippingStatus> allowed = getAllowedShippingStatuses(order);
        if (!allowed.contains(newStatus)) {
            if (order.getPaymentStatus() != PaymentStatus.PAID) {
                throw new IllegalStateException("El envío no puede avanzar porque el pago aún no está aprobado.");
            }
            throw new IllegalStateException("Transición de estado de envío inválida de " + order.getShippingStatus() + " a " + newStatus);
        }
    }

    public boolean canUpdateOrderStatus(Order order) {
        return getAllowedOrderStatuses(order).size() > 1;
    }

    public boolean canUpdateShippingStatus(Order order) {
        return getAllowedShippingStatuses(order).size() > 1;
    }

    public boolean canUpdateTrackingNumber(Order order) {
        return order.getPaymentStatus() == PaymentStatus.PAID &&
               (order.getShippingStatus() == ShippingStatus.SHIPPED || order.getShippingStatus() == ShippingStatus.IN_TRANSIT);
    }

    public String getOrderStatusHelpMessage(Order order) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return "Una orden cancelada no puede reactivarse desde este panel.";
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            return "Una orden completada no puede regresar a estados anteriores.";
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            return "Aprueba el pago antes de confirmar o avanzar esta orden.";
        }
        return "El pago está aprobado. Puedes gestionar el estado de la orden.";
    }

    public String getShippingStatusHelpMessage(Order order) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return "No se puede actualizar el envío de una orden cancelada.";
        }
        if (order.getStatus() == OrderStatus.COMPLETED || order.getShippingStatus() == ShippingStatus.DELIVERED) {
            return "Este pedido ya fue entregado.";
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            return "El envío podrá actualizarse cuando el pago esté aprobado.";
        }
        return "Gestiona el estado de envío según el avance del paquete.";
    }

    public boolean canCancelOrder(Order order) {
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.COMPLETED) {
            return false;
        }
        ShippingStatus shipping = order.getShippingStatus();
        if (shipping == ShippingStatus.SHIPPED || shipping == ShippingStatus.IN_TRANSIT || shipping == ShippingStatus.DELIVERED || shipping == ShippingStatus.RETURNED) {
            return false;
        }
        return true;
    }

    public boolean requiresCancelReason() {
        return true;
    }

    public String getCancelOrderHelpMessage(Order order) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return "La orden ya se encuentra cancelada.";
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            return "No se puede cancelar una orden completada.";
        }
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            return "No se puede cancelar directamente una orden pagada. Requiere flujo de reembolso.";
        }
        ShippingStatus shipping = order.getShippingStatus();
        if (shipping == ShippingStatus.SHIPPED || shipping == ShippingStatus.IN_TRANSIT || shipping == ShippingStatus.DELIVERED || shipping == ShippingStatus.RETURNED) {
            return "No se puede cancelar una orden que ya inició envío.";
        }
        return "Puedes cancelar esta orden mientras no esté pagada ni enviada.";
    }
}
