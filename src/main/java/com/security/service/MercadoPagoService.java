package com.security.service;

import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferencePayerRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.preference.Preference;
import com.security.config.MercadoPagoProperties;
import com.security.entity.Order;
import com.security.entity.OrderItem;
import com.security.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoService {

    private final MercadoPagoProperties properties;

    /**
     * Crea una preferencia de Checkout Pro en Mercado Pago a partir de una orden interna.
     * Retorna la URL de checkout (InitPoint o SandboxInitPoint dependiendo del ambiente).
     */
    public Preference createPreferenceForOrder(Order order, String externalReference) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Mercado Pago no está habilitado en este momento.");
        }

        try {
            PreferenceClient client = new PreferenceClient();
            
            // 1. Mapear Items
            List<PreferenceItemRequest> items = new ArrayList<>();
            java.math.BigDecimal preferenceTotal = java.math.BigDecimal.ZERO;
            
            for (OrderItem orderItem : order.getItems()) {
                PreferenceItemRequest itemRequest = PreferenceItemRequest.builder()
                        .title(orderItem.getProductName())
                        .quantity(orderItem.getQuantity())
                        .unitPrice(orderItem.getUnitPrice())
                        .currencyId("MXN")
                        .build();
                items.add(itemRequest);
                preferenceTotal = preferenceTotal.add(orderItem.getUnitPrice().multiply(new java.math.BigDecimal(orderItem.getQuantity())));
            }

            // Agregar Envío
            if (order.getShipping() != null && order.getShipping().compareTo(java.math.BigDecimal.ZERO) > 0) {
                PreferenceItemRequest shippingItem = PreferenceItemRequest.builder()
                        .title("Costo de Envío")
                        .quantity(1)
                        .unitPrice(order.getShipping())
                        .currencyId("MXN")
                        .build();
                items.add(shippingItem);
                preferenceTotal = preferenceTotal.add(order.getShipping());
            }

            // Agregar IVA
            if (order.getTax() != null && order.getTax().compareTo(java.math.BigDecimal.ZERO) > 0) {
                PreferenceItemRequest taxItem = PreferenceItemRequest.builder()
                        .title("IVA (16%)")
                        .quantity(1)
                        .unitPrice(order.getTax())
                        .currencyId("MXN")
                        .build();
                items.add(taxItem);
                preferenceTotal = preferenceTotal.add(order.getTax());
            }

            // Manejar Descuento: Si Mercado Pago no permite item negativo de forma nativa en esta versión del SDK, colapsar en un item
            if (order.getDiscount() != null && order.getDiscount().compareTo(java.math.BigDecimal.ZERO) > 0) {
                items.clear();
                PreferenceItemRequest unifiedItem = PreferenceItemRequest.builder()
                        .title("Pedido " + order.getOrderNumber())
                        .quantity(1)
                        .unitPrice(order.getTotal())
                        .currencyId("MXN")
                        .build();
                items.add(unifiedItem);
                preferenceTotal = order.getTotal();
            }

            // Validación Obligatoria antes de crear la preferencia
            if (preferenceTotal.compareTo(order.getTotal()) != 0) {
                log.error("Mercado Pago validation failed. OrderId={}, preferenceTotal={}, orderTotal={}", 
                          order.getId(), preferenceTotal, order.getTotal());
                throw new com.security.exception.MercadoPagoPreferenceException(
                        "No se pudo iniciar el pago porque el total de los items (" + preferenceTotal + ") no coincide con el total de la orden (" + order.getTotal() + ")");
            }
            
            // 2. Mapear Payer
            User user = order.getUser();
            PreferencePayerRequest payer = null;
            if (user != null) {
                payer = PreferencePayerRequest.builder()
                        .email(user.getEmail())
                        .name(user.getFirstName())
                        .surname(user.getLastName())
                        .build();
            }
            
            // 3. Back Urls
            String frontendUrl = normalizeBaseUrl(properties.getFrontendBaseUrl());
            if (frontendUrl == null || frontendUrl.trim().isEmpty()) {
                throw new IllegalStateException("No se puede iniciar el pago: FRONTEND_BASE_URL no está configurada correctamente.");
            }
            
            String successUrl = frontendUrl + "/payment/mercado-pago/success?orderId=" + order.getId() + "&externalReference=" + externalReference;
            String failureUrl = frontendUrl + "/payment/mercado-pago/failure?orderId=" + order.getId() + "&externalReference=" + externalReference;
            String pendingUrl = frontendUrl + "/payment/mercado-pago/pending?orderId=" + order.getId() + "&externalReference=" + externalReference;
            
            PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                    .success(successUrl)
                    .failure(failureUrl)
                    .pending(pendingUrl)
                    .build();

            // 4. Notification Url (Webhook Fase 7C)
            String backendUrl = normalizeBaseUrl(properties.getBackendBaseUrl());
            String webhookUrl = backendUrl != null ? backendUrl + "/api/webhooks/mercado-pago" : null;
            if (webhookUrl == null) {
                log.warn("MercadoPagoService: backendBaseUrl no configurado. notification_url no se enviará a Mercado Pago. Los webhooks de Fase 7C no funcionarán.");
            } else {
                log.debug("MercadoPagoService: notification_url={}", webhookUrl);
            }

            // 5. Build Preference Request
            PreferenceRequest request = PreferenceRequest.builder()
                    .items(items)
                    .payer(payer)
                    .backUrls(backUrls)
                    .autoReturn("approved")
                    .externalReference(externalReference)
                    .notificationUrl(webhookUrl)
                    .build();

            // 6. Create in Mercado Pago
            log.info("Creating Mercado Pago preference:\n" +
                    "orderId={}\n" +
                    "orderNumber={}\n" +
                    "subtotal={}\n" +
                    "shipping={}\n" +
                    "tax={}\n" +
                    "discount={}\n" +
                    "orderTotal={}\n" +
                    "preferenceItemsTotal={}\n" +
                    "itemsCount={}\n" +
                    "externalReference={}",
                    order.getId(),
                    order.getOrderNumber(),
                    order.getSubtotal(),
                    order.getShipping() != null ? order.getShipping() : java.math.BigDecimal.ZERO,
                    order.getTax() != null ? order.getTax() : java.math.BigDecimal.ZERO,
                    order.getDiscount() != null ? order.getDiscount() : java.math.BigDecimal.ZERO,
                    order.getTotal(),
                    preferenceTotal,
                    items.size(),
                    externalReference);

            Preference preference = client.create(request);
            log.info("Preferencia Mercado Pago creada para Orden {}. Preference ID: {}", order.getId(), preference.getId());
            
            return preference;
            
        } catch (MPException e) {
            log.error("Error SDK MP al crear preferencia para orden {}: {}", order.getId(), e.getMessage());
            throw new com.security.exception.MercadoPagoPreferenceException("Error interno al comunicarse con Mercado Pago", e);
        } catch (MPApiException e) {
            log.error("Error API MP al crear preferencia orden {}. Status: {}. Details: {}", 
                      order.getId(), e.getApiResponse().getStatusCode(), e.getApiResponse().getContent());
            throw new com.security.exception.MercadoPagoPreferenceException("Mercado Pago rechazó la creación del pago", e.getApiResponse().getStatusCode(), e.getApiResponse().getContent(), e);
        }
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String normalized = url.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
