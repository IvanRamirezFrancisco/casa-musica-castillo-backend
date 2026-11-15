package com.security.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Configuración de filtro para cachear el body de los webhooks de Mercado Pago.
 *
 * Spring Boot por defecto no permite leer el InputStream más de una vez.
 * Este filtro envuelve la petición con ContentCachingRequestWrapper SOLO para
 * el path del webhook, sin afectar el rendimiento del resto de la aplicación.
 *
 * Esto permite que el Controller lea el raw body para calcular el payload_hash
 * y que Jackson también pueda deserializarlo al DTO.
 */
@Configuration
public class WebhookRequestCacheConfig {

    @Bean
    public FilterRegistrationBean<Filter> webhookRequestCacheFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter((request, response, chain) -> {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            // Solo envolver si es el path del webhook y es POST
            if ("POST".equalsIgnoreCase(httpRequest.getMethod())
                    && httpRequest.getRequestURI().contains("/api/webhooks/mercado-pago")) {
                chain.doFilter(new ContentCachingRequestWrapper(httpRequest, 0), response);
            } else {
                chain.doFilter(request, response);
            }
        });
        registration.addUrlPatterns("/api/webhooks/*");
        registration.setName("webhookRequestCacheFilter");
        registration.setOrder(1);
        return registration;
    }
}
