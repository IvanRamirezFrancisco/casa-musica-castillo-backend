package com.security.job;

import com.security.entity.Payment;
import com.security.enums.PaymentProvider;
import com.security.repository.PaymentRepository;
import com.security.service.MercadoPagoWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MercadoPagoReconciliationJobTest {
    @Test
    void theDuplicateInstanceCanOmitOnlyTheReconciliationBean() {
        var runner = new ApplicationContextRunner()
                .withBean(PaymentRepository.class, () -> mock(PaymentRepository.class))
                .withBean(MercadoPagoWebhookService.class, () -> mock(MercadoPagoWebhookService.class))
                .withUserConfiguration(MercadoPagoReconciliationJob.class);

        runner.run(context -> assertThat(context).hasSingleBean(MercadoPagoReconciliationJob.class));
        runner.withPropertyValues("app.mercadopago.reconciliation.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(MercadoPagoReconciliationJob.class);
            assertThat(context).hasSingleBean(MercadoPagoWebhookService.class);
        });
    }

    @Test
    void delegatesOnlyPendingPaymentsThatHaveAProviderReferenceForRequery() {
        var payments = mock(PaymentRepository.class);
        var webhook = mock(MercadoPagoWebhookService.class);
        var referenced = new Payment();
        referenced.setId(1L);
        referenced.setProviderPaymentId("123");
        var unreferenced = new Payment();
        unreferenced.setId(2L);
        when(payments.findByProviderAndStatusInAndCreatedAtBefore(eq(PaymentProvider.MERCADO_PAGO), any(), any()))
                .thenReturn(List.of(referenced, unreferenced));

        new MercadoPagoReconciliationJob(payments, webhook).reconcilePendingPayments();

        verify(webhook).requeryPayment(1L, null);
        verifyNoMoreInteractions(webhook);
    }
}
