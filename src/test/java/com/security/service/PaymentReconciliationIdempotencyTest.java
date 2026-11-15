package com.security.service;

import com.security.entity.Order;
import com.security.entity.Payment;
import com.security.enums.PaymentStatus;
import com.security.enums.PaymentTransactionStatus;
import com.security.repository.OrderRepository;
import com.security.repository.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentReconciliationIdempotencyTest {
    @Test
    void replayingTheSameApprovedPaymentPreservesTheOriginalConfirmation() {
        var payments = mock(PaymentRepository.class);
        var events = mock(PaymentEventService.class);
        var orders = mock(OrderRepository.class);
        var service = new PaymentService(payments, events, orders, null, null, null, null);

        var order = new Order();
        order.setId(2L);
        order.setPaymentStatus(PaymentStatus.PAID);
        when(orders.findById(2L)).thenReturn(Optional.of(order));

        var payment = new Payment();
        var paidAt = LocalDateTime.of(2026, 10, 1, 10, 0);
        payment.setId(1L);
        payment.setOrder(order);
        payment.setStatus(PaymentTransactionStatus.PAID);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCurrency("MXN");
        payment.setExternalReference("fixture");
        payment.setProviderPaymentId("123");
        payment.setRawProviderStatus("approved");
        payment.setPaidAt(paidAt);

        var approved = mock(com.mercadopago.resources.payment.Payment.class);
        when(approved.getId()).thenReturn(123L);
        when(approved.getStatus()).thenReturn("approved");
        when(approved.getCurrencyId()).thenReturn("MXN");
        when(approved.getTransactionAmount()).thenReturn(new BigDecimal("100.00"));
        when(approved.getExternalReference()).thenReturn("fixture");

        service.applyMercadoPagoStatus(payment, approved, "synthetic-reconciliation");

        verifyNoInteractions(payments, events);
        assertThat(payment.getPaidAt()).isEqualTo(paidAt);
    }
}
