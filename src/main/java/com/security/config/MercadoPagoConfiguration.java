package com.security.config;

import com.mercadopago.MercadoPagoConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoConfiguration {

    private final MercadoPagoProperties mercadoPagoProperties;

    @PostConstruct
    public void init() {
        if (!mercadoPagoProperties.isEnabled()) {
            log.info("Mercado Pago Checkout Pro está DESHABILITADO.");
            return;
        }

        String token = mercadoPagoProperties.getAccessToken();
        boolean tokenPresent = token != null && !token.trim().isEmpty();
        
        String publicKey = mercadoPagoProperties.getPublicKey();
        boolean publicKeyPresent = publicKey != null && !publicKey.trim().isEmpty();

        String frontendBaseUrl = mercadoPagoProperties.getFrontendBaseUrl();
        boolean frontendBaseUrlPresent = frontendBaseUrl != null && !frontendBaseUrl.trim().isEmpty();

        String backendBaseUrl = mercadoPagoProperties.getBackendBaseUrl();
        String environment = mercadoPagoProperties.getEnvironment();

        log.info("Mercado Pago config loaded:\n" +
                 "enabled=true\n" +
                 "environment={}\n" +
                 "frontendBaseUrlPresent={}\n" +
                 "frontendBaseUrl={}\n" +
                 "backendBaseUrl={}\n" +
                 "accessTokenPresent={}\n" +
                 "publicKeyPresent={}",
                 environment, frontendBaseUrlPresent, frontendBaseUrl, backendBaseUrl, tokenPresent, publicKeyPresent);

        if (!tokenPresent) {
            log.warn("Mercado Pago está habilitado pero NO se configuró un ACCESS_TOKEN.");
            throw new IllegalStateException("Mercado Pago habilitado pero falta ACCESS_TOKEN");
        }

        if (!frontendBaseUrlPresent) {
            throw new IllegalStateException("Mercado Pago está habilitado pero falta configurar MERCADO_PAGO_FRONTEND_BASE_URL.");
        }

        if (!frontendBaseUrl.startsWith("http://") && !frontendBaseUrl.startsWith("https://")) {
            throw new IllegalStateException("MERCADO_PAGO_FRONTEND_BASE_URL debe iniciar con http:// o https://");
        }

        if (frontendBaseUrl.endsWith("/")) {
            throw new IllegalStateException("MERCADO_PAGO_FRONTEND_BASE_URL no debe terminar con slash (/)");
        }

        if (backendBaseUrl != null && !backendBaseUrl.trim().isEmpty()) {
            if (!backendBaseUrl.startsWith("http://") && !backendBaseUrl.startsWith("https://")) {
                throw new IllegalStateException("MERCADO_PAGO_BACKEND_BASE_URL debe iniciar con http:// o https://");
            }
            if (backendBaseUrl.endsWith("/")) {
                throw new IllegalStateException("MERCADO_PAGO_BACKEND_BASE_URL no debe terminar con slash (/)");
            }
        }

        if (environment == null || environment.trim().isEmpty()) {
            throw new IllegalStateException("MERCADO_PAGO_ENVIRONMENT es obligatorio si Mercado Pago está habilitado.");
        }

        MercadoPagoConfig.setAccessToken(token.trim());
        log.info("Mercado Pago SDK inicializado con éxito.");
    }
}
