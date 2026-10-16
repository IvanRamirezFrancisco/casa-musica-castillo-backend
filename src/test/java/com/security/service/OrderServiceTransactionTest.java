package com.security.service;

import com.security.entity.Order;
import com.security.entity.User;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.repository.OrderRepository;
import com.security.event.AuditSuccessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTransactionTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentProofService paymentProofService;
    
    @Mock
    private com.security.repository.PaymentProofRepository paymentProofRepository;

    @Mock
    private com.security.repository.OrderPickupAuthorizationRepository orderPickupAuthorizationRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private OrderTimelineEventService timelineEventService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    
    @Mock
    private com.security.repository.UserRepository userRepository;

    @InjectMocks
    private OrderService orderService;

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(100L);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setItems(Collections.emptyList());
        
        User user = new User();
        user.setId(1L);
        order.setUser(user);
    }

    @Test
    void testCancelOrder_WhenTimelineFails_ShouldRollbackAndNotPublishSuccessAudit() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        // Simulamos que al insertar en el timeline falla la base de datos (después de saveAndFlush y paymentService)
        doThrow(new RuntimeException("Simulated database constraint or connectivity error in timeline"))
                .when(timelineEventService).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class, () -> 
                orderService.cancelOrder(100L, "Cancelación simulada", "SYSTEM", 1L)
        );

        // Verificamos que saveAndFlush fue llamado
        verify(orderRepository, times(1)).saveAndFlush(any(Order.class));
        
        // Verificamos que el pago se intentó cancelar
        verify(paymentService, times(1)).cancelActivePaymentsForOrder(eq(100L), anyString(), eq("SYSTEM"), eq(1L));

        // CRÍTICO: Como publishEvent está al final de cancelOrder, si timelineEventService falla, 
        // nunca se llega a invocar la publicación del evento de éxito.
        verify(applicationEventPublisher, never()).publishEvent(any(AuditSuccessEvent.class));
        
        // ADEMÁS: NO debe haberse llamado a auditLogService.log directo con success=true
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), anyLong(), any(), any(), anyString(), eq(true));
        
        // PERO SÍ debió publicarse el fallo si saveAndFlush fallara. Como saveAndFlush pasó y falló timeline, 
        // cancelOrder dejará que la excepción burbujee (y Spring hará rollback).
    }

    @Test
    void testCancelOrder_SystemExpiration_RecordsActorTypeSystem() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        orderService.cancelOrder(100L, "Expiración por sistema", "SYSTEM_EXPIRATION", null);

        verify(timelineEventService).recordEvent(
                any(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), 
                eq(null), eq("SYSTEM"), anyString(), anyString(), any());
    }

    @Test
    void testCancelOrder_Admin_RecordsActorTypeAdmin() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        
        User adminUser = new User();
        adminUser.setId(2L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(adminUser));

        orderService.cancelOrder(100L, "Cancelado por admin", "ADMIN", 2L);

        verify(timelineEventService).recordEvent(
                any(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), 
                eq(adminUser), eq("ADMIN"), anyString(), anyString(), any());
    }

    @Test
    void testCancelOrder_Customer_RecordsActorTypeCustomer() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        
        when(userRepository.findById(1L)).thenReturn(Optional.of(order.getUser()));

        orderService.cancelOrder(100L, "Cancelado por cliente", "CUSTOMER", 1L);

        verify(timelineEventService).recordEvent(
                any(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), 
                eq(order.getUser()), eq("CUSTOMER"), anyString(), anyString(), any());
    }

    @Test
    void testCancelOrder_Success_PublishesAuditSuccessEvent() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        orderService.cancelOrder(100L, "Éxito", "SYSTEM", null);

        verify(applicationEventPublisher, times(1)).publishEvent(any(AuditSuccessEvent.class));
    }
}
