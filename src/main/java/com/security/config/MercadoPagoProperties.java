package com.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.mercadopago")
@Data
public class MercadoPagoProperties {
    private boolean enabled;
    private String environment;
    private String accessToken;
    private String publicKey;
    private String webhookSecret;
    private String frontendBaseUrl;
    private String backendBaseUrl;
}
