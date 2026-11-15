package com.security.config;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Configuración de beans de infraestructura general.
 *
 * <p>
 * El {@link RestTemplate} se declara como singleton gestionado por Spring en
 * lugar
 * de instanciarse con {@code new RestTemplate()} en cada llamada (anti-patrón
 * que
 * deja conexiones abiertas y no aplica timeouts). Timeouts configurados:
 * </p>
 * <ul>
 * <li>Conexión: 5 s — tiempo máximo para establecer el socket TCP.</li>
 * <li>Lectura: 10 s — tiempo máximo esperando la respuesta del servidor.</li>
 * </ul>
 */
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }
}
