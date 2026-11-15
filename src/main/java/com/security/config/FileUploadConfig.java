package com.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuración para servir archivos estáticos (imágenes subidas)
 */
@Configuration
public class FileUploadConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(FileUploadConfig.class);

    @Value("${upload.path:uploads/products}")
    private String uploadPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Convertir a path absoluto
        Path absolutePath = Paths.get(uploadPath).toAbsolutePath();

        // Mapear /uploads/products/** a la carpeta física uploads/products/
        registry.addResourceHandler("/uploads/products/**")
                .addResourceLocations("file:" + absolutePath + "/")
                .setCachePeriod(3600);

        log.info("Serving static files from: {}", absolutePath);
    }

    /**
     * Bean para manejar multipart requests
     */
    @Bean
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }
}
