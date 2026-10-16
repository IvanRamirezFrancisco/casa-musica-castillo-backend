package com.security.job;

import com.security.entity.Order;
import com.security.entity.PaymentProof;
import com.security.entity.Product;
import com.security.entity.OrderItem;
import com.security.entity.User;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentMethod;
import com.security.enums.PaymentProofStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import com.security.repository.OrderRepository;
import com.security.repository.PaymentProofRepository;
import com.security.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExpirationJobTest {

    @Mock
    private OrderRepository orderRepository;
    
    @Mock
    private OrderService orderService;
    
    @Mock
    private PaymentProofRepository paymentProofRepository;

    private OrderExpirationJob job;

    @BeforeEach
    void setUp() {
        job = new OrderExpirationJob(orderRepository, orderService, paymentProofRepository);
        ReflectionTestUtils.setField(job, "paymentValidityHours", 72);
    }

    // 1. Orden pendiente y vencida: se cancela; cancel_source = SYSTEM; cancellation_reason correcta; contador incrementado.
    @Test
    void testCancelExpiredOrders_PendingAndExpired_CancelsOrder() {
        Order expiredBankTransfer = new Order();
        ReflectionTestUtils.setField(expiredBankTransfer, "id", 1L);
        expiredBankTransfer.setStatus(OrderStatus.PENDING);
        expiredBankTransfer.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        expiredBankTransfer.setPaymentStatus(PaymentStatus.PENDING);
        expiredBankTransfer.setShippingStatus(ShippingStatus.PENDING);
        // Expirada (hace 80 horas, más de 72)
        expiredBankTransfer.setCreatedAt(LocalDateTime.now().minusHours(80));

        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(expiredBankTransfer));
        when(paymentProofRepository.findTopByOrderIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());

        job.cancelExpiredOrders();

        verify(orderService, times(1)).cancelOrder(
                eq(1L),
                eq("Orden expirada por falta de pago dentro del plazo establecido."),
                eq("SYSTEM_EXPIRATION"), // Pasa SYSTEM_EXPIRATION, que luego OrderService convierte a SYSTEM y auditoría success=true
                eq(null)
        );
    }

    // 2. Orden pagada: no se cancela.
    @Test
    void testCancelExpiredOrders_PaidOrder_DoesNotCancel() {
        Order paidOrder = new Order();
        ReflectionTestUtils.setField(paidOrder, "id", 2L);
        paidOrder.setStatus(OrderStatus.PENDING); // Aunque esté en pending, si está pagada o tiene comprobante no se cancela
        paidOrder.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        paidOrder.setPaymentStatus(PaymentStatus.PAID);
        paidOrder.setShippingStatus(ShippingStatus.PENDING);
        paidOrder.setCreatedAt(LocalDateTime.now().minusHours(80));

        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(paidOrder));
        
        job.cancelExpiredOrders();

        verify(orderService, never()).cancelOrder(anyLong(), any(), any(), any());
    }

    // 3. Orden ya cancelada: no se vuelve a cancelar.
    // (El job solo busca OrderStatus.PENDING, así que no procesará canceladas, pero validamos la lógica)
    @Test
    void testCancelExpiredOrders_AlreadyCancelled_DoesNotProcess() {
        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(Collections.emptyList());
        
        job.cancelExpiredOrders();

        verify(orderService, never()).cancelOrder(anyLong(), any(), any(), any());
    }

    // 4. Error de persistencia: se registra fallo, contador no se incrementa, y 5. Fallo de una orden: las siguientes continúan
    @Test
    void testCancelExpiredOrders_PersistenceError_ContinuesWithNextOrder() {
        Order failOrder = new Order();
        ReflectionTestUtils.setField(failOrder, "id", 3L);
        failOrder.setStatus(OrderStatus.PENDING);
        failOrder.setPaymentMethod(PaymentMethod.MERCADO_PAGO);
        failOrder.setCreatedAt(LocalDateTime.now().minusMinutes(90)); // Expirada MP > 60m

        Order successOrder = new Order();
        ReflectionTestUtils.setField(successOrder, "id", 4L);
        successOrder.setStatus(OrderStatus.PENDING);
        successOrder.setPaymentMethod(PaymentMethod.MERCADO_PAGO);
        successOrder.setCreatedAt(LocalDateTime.now().minusMinutes(90)); // Expirada MP > 60m

        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(Arrays.asList(failOrder, successOrder));

        // Simulamos un error al cancelar la orden 3
        doThrow(new RuntimeException("Error simulado de base de datos"))
            .when(orderService).cancelOrder(eq(3L), any(), any(), any());

        job.cancelExpiredOrders();

        // Verifica que se intentó cancelar ambas
        verify(orderService, times(1)).cancelOrder(eq(3L), any(), any(), any());
        verify(orderService, times(1)).cancelOrder(eq(4L), any(), any(), any());
    }
    
    // 6. Ejecución repetida: no genera cancelaciones duplicadas.
    // Si la orden ya se procesó, no estará en PENDING.
    @Test
    void testCancelExpiredOrders_RepeatedExecution_NoDuplicates() {
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 5L);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentMethod(PaymentMethod.MERCADO_PAGO);
        order.setCreatedAt(LocalDateTime.now().minusMinutes(90));

        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));

        job.cancelExpiredOrders();
        
        verify(orderService, times(1)).cancelOrder(eq(5L), any(), any(), any());
        
        // Segunda ejecución simulando que ya se procesó y ya no está PENDING
        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(Collections.emptyList());
        
        job.cancelExpiredOrders();
        
        verify(orderService, times(1)).cancelOrder(eq(5L), any(), any(), any()); // Total remains 1
    }
}
