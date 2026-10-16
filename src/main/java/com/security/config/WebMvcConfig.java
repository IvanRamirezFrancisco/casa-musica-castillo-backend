package com.security.config;

import com.security.security.CurrentUserArgumentResolver;
import com.security.interceptor.SecurityInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    @Autowired
    private SecurityInterceptor securityInterceptor;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/test/public", "/actuator/**");
    }

    // CORS: NO se configura aquí. La única fuente de verdad es
    // SecurityConfig.corsConfigurationSource(), alimentada por la propiedad
    // cors.allowed-origins (variable de entorno CORS_ORIGINS en producción).
    // Mantener una segunda configuración en WebMvcConfigurer generaba reglas
    // contradictorias con orígenes hardcodeados.
}