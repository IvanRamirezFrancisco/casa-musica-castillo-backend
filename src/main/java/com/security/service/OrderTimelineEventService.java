package com.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.dto.response.OrderTimelineEventDTO;
import com.security.entity.Order;
import com.security.entity.OrderTimelineEvent;
import com.security.entity.User;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import com.security.repository.OrderTimelineEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderTimelineEventService {

    private final OrderTimelineEventRepository repository;
    private final ObjectMapper objectMapper; // Mantener por si se requiere en el futuro, aunque no se usa ahora para metadataJson.
    private final PlatformTransactionManager transactionManager;

    // Quitado @Transactional para manejarlo manualmente y no heredar ni contaminar la transacción actual.
    public void recordEvent(
            Order order,
            String eventType,
            String title,
            String description,
            OrderStatus prevOrderStatus,
            OrderStatus newOrderStatus,
            PaymentStatus prevPaymentStatus,
            PaymentStatus newPaymentStatus,
            ShippingStatus prevShippingStatus,
            ShippingStatus newShippingStatus,
            User actor,
            String actorType,
            String source,
            String visibility,
            Map<String, Object> metadata
    ) {
        OrderTimelineEvent event = OrderTimelineEvent.builder()
                .order(order)
                .eventType(eventType)
                .title(title)
                .description(description)
                .previousOrderStatus(prevOrderStatus)
                .newOrderStatus(newOrderStatus)
                .previousPaymentStatus(prevPaymentStatus)
                .newPaymentStatus(newPaymentStatus)
                .previousShippingStatus(prevShippingStatus)
                .newShippingStatus(newShippingStatus)
                .actorUser(actor)
                .actorType(actorType)
                .source(source)
                .visibility(visibility)
                .metadataJson(metadata == null || metadata.isEmpty() ? null : metadata)
                .build();

        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    saveEventInNewTransaction(event);
                }
            });
        } else {
            saveEventInNewTransaction(event);
        }
    }

    private void saveEventInNewTransaction(OrderTimelineEvent event) {
        try {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
            transactionTemplate.execute(status -> {
                repository.save(event);
                return null;
            });
            log.info("Timeline event recorded for Order {}: {}", event.getOrder().getId(), event.getEventType());
        } catch (Exception e) {
            log.error("Failed to record timeline event for Order {}: {}", event.getOrder().getId(), e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<OrderTimelineEventDTO> getAdminTimeline(Long orderId) {
        return repository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OrderTimelineEventDTO> getCustomerTimeline(Long orderId) {
        // Customers can see BOTH and CUSTOMER visibility events
        return repository.findByOrderIdAndVisibilityInOrderByCreatedAtDesc(orderId, List.of("BOTH", "CUSTOMER")).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    private OrderTimelineEventDTO toDTO(OrderTimelineEvent event) {
        String actorLabel = "Sistema";
        if ("CUSTOMER".equals(event.getActorType())) {
            actorLabel = "Cliente";
        } else if ("ADMIN".equals(event.getActorType())) {
            actorLabel = "Administrador";
        } else if ("WEBHOOK".equals(event.getActorType())) {
            actorLabel = "Mercado Pago";
        }

        return OrderTimelineEventDTO.builder()
                .id(event.getId())
                .eventType(event.getEventType())
                .title(event.getTitle())
                .description(event.getDescription())
                .orderStatusBefore(event.getPreviousOrderStatus())
                .orderStatusAfter(event.getNewOrderStatus())
                .paymentStatusBefore(event.getPreviousPaymentStatus())
                .paymentStatusAfter(event.getNewPaymentStatus())
                .shippingStatusBefore(event.getPreviousShippingStatus())
                .shippingStatusAfter(event.getNewShippingStatus())
                .actorLabel(actorLabel)
                .visibility(event.getVisibility())
                .createdAt(event.getCreatedAt())
                .build();
    }
}
