package com.security.service;

import com.security.dto.ShippingCalculationResult;
import com.security.entity.ShippingSettings;
import com.security.repository.ShippingSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;

@Service
public class DeliveryMethodService {

    @Autowired
    private ShippingSettingsRepository shippingSettingsRepository;

    public ShippingCalculationResult calculateDelivery(String deliveryOption, String state, String city, String postalCode) {
        ShippingSettings settings = shippingSettingsRepository.findById(1L).orElse(new ShippingSettings());

        if ("PICKUP_STORE".equalsIgnoreCase(deliveryOption)) {
            if (!settings.getPickupEnabled()) {
                return new ShippingCalculationResult(
                        null, null, null, false, false, false,
                        "Recoger en tienda no está disponible.",
                        null, null, null, null
                );
            }
            return new ShippingCalculationResult(
                    "PICKUP_STORE",
                    settings.getPickupLabel(),
                    BigDecimal.ZERO,
                    false,
                    false,
                    true,
                    "Recoge tu pedido directamente en tienda.",
                    null, null, null, null
            );
        } else if ("LOCAL_DELIVERY".equalsIgnoreCase(deliveryOption)) {
            if (!settings.getLocalDeliveryEnabled()) {
                return new ShippingCalculationResult(
                        null, null, null, true, false, false,
                        "Envío local no está disponible.",
                        null, null, null, null
                );
            }

            if (state == null || state.isBlank() || city == null || city.isBlank() || postalCode == null || postalCode.isBlank()) {
                return new ShippingCalculationResult(
                        "LOCAL_DELIVERY",
                        "Envío local",
                        null,
                        true,
                        false,
                        false,
                        "Completa tu dirección para calcular el envío local.",
                        null, null, null, null
                );
            }

            boolean isLocal = isLocalAddress(settings, state, city, postalCode);
            if (isLocal) {
                return new ShippingCalculationResult(
                        "LOCAL_DELIVERY",
                        "Envío local",
                        settings.getLocalDeliveryCost(),
                        true,
                        false,
                        true,
                        "Envío local disponible para tu dirección.",
                        null, null, null, null
                );
            } else {
                return new ShippingCalculationResult(
                        "EXTERNAL_SHIPPING_QUOTE",
                        "Envío fuera de zona",
                        null,
                        true,
                        true,
                        false,
                        "Tu dirección está fuera de la zona local y requiere cotización de envío.",
                        settings.getContactWhatsapp(),
                        settings.getContactPhone(),
                        settings.getQuoteMessage(),
                        settings.getBusinessHours()
                );
            }
        } else if ("EXTERNAL_SHIPPING_QUOTE".equalsIgnoreCase(deliveryOption) || "DELIVERY".equalsIgnoreCase(deliveryOption)) {
            if (!settings.getExternalShippingEnabled()) {
                return new ShippingCalculationResult(
                        null, null, null, true, false, false,
                        "Envío fuera de zona no disponible.",
                        null, null, null, null
                );
            }

            if (state == null || state.isBlank() || city == null || city.isBlank() || postalCode == null || postalCode.isBlank()) {
                return new ShippingCalculationResult(
                        "EXTERNAL_SHIPPING_QUOTE",
                        "Envío foráneo",
                        null,
                        true,
                        true,
                        false,
                        "Completa tu dirección para solicitar una cotización de envío.",
                        settings.getContactWhatsapp(),
                        settings.getContactPhone(),
                        settings.getQuoteMessage(),
                        settings.getBusinessHours()
                );
            }

            if ("FIXED_COST".equalsIgnoreCase(settings.getExternalShippingMode())) {
                return new ShippingCalculationResult(
                        "EXTERNAL_SHIPPING_FIXED",
                        "Envío foráneo",
                        settings.getExternalShippingFixedCost() != null ? settings.getExternalShippingFixedCost() : BigDecimal.ZERO,
                        true,
                        false,
                        true,
                        "Costo de envío fijo aplicado.",
                        null, null, null, null
                );
            } else {
                // QUOTE_REQUIRED
                return new ShippingCalculationResult(
                        "EXTERNAL_SHIPPING_QUOTE",
                        "Envío foráneo (Por cotizar)",
                        null,
                        true,
                        true,
                        false,
                        "Tu dirección requiere cotización de envío. Contacta al negocio para confirmar el costo antes de continuar.",
                        settings.getContactWhatsapp(),
                        settings.getContactPhone(),
                        settings.getQuoteMessage(),
                        settings.getBusinessHours()
                );
            }
        }

        return new ShippingCalculationResult(
                null, null, null, true, false, false,
                "Opción de entrega no válida.",
                null, null, null, null
        );
    }

    private boolean isLocalAddress(ShippingSettings settings, String state, String city, String postalCode) {
        if (state == null && city == null && postalCode == null) {
            return false;
        }

        // Validate by postal code first if configured
        if (postalCode != null && !postalCode.isBlank() && settings.getLocalDeliveryPostalCodes() != null && !settings.getLocalDeliveryPostalCodes().isBlank()) {
            String[] configuredCPs = settings.getLocalDeliveryPostalCodes().split(",");
            for (String cp : configuredCPs) {
                if (cp.trim().equalsIgnoreCase(postalCode.trim())) {
                    return true;
                }
            }
        }

        // Validate by state + city as fallback
        if (state != null && city != null) {
            String normalizedInputState = normalizeString(state);
            String normalizedInputCity = normalizeString(city);
            String normalizedConfigState = normalizeString(settings.getLocalDeliveryState());
            String normalizedConfigCity = normalizeString(settings.getLocalDeliveryCity());

            if (normalizedInputState.contains(normalizedConfigState) || normalizedConfigState.contains(normalizedInputState)) {
                if (normalizedInputCity.contains(normalizedConfigCity) || normalizedConfigCity.contains(normalizedInputCity)) {
                    return true;
                }
            }
        }

        return false;
    }

    private String normalizeString(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input.trim().toLowerCase(), Normalizer.Form.NFD);
        return normalized.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
    }
}
