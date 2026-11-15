package com.security.config;

import com.security.service.CloudinaryStorageService;
import com.security.service.LocalStorageService;
import com.security.service.StorageService;
import com.security.util.ImageValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Value("${storage.provider:CLOUDINARY}")
    private String storageProvider;

    @Value("${CLOUDINARY_CLOUD_NAME:}")
    private String cloudName;

    @Value("${CLOUDINARY_API_KEY:}")
    private String apiKey;

    @Value("${CLOUDINARY_API_SECRET:}")
    private String apiSecret;

    @Value("${storage.local.products-path:uploads/products}")
    private String productsPath;

    @Value("${storage.local.brands-path:uploads/brands}")
    private String brandsPath;

    @Bean
    public StorageService storageService(ImageValidator imageValidator) {
        if ("LOCAL".equalsIgnoreCase(storageProvider)) {
            return new LocalStorageService(imageValidator, productsPath, brandsPath);
        } else if ("CLOUDINARY".equalsIgnoreCase(storageProvider)) {
            if (cloudName.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
                throw new IllegalStateException("El proveedor STORAGE_PROVIDER=CLOUDINARY está configurado, pero faltan las variables de entorno: CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET");
            }
            return new CloudinaryStorageService(imageValidator, cloudName, apiKey, apiSecret);
        } else {
            throw new IllegalArgumentException("Proveedor de almacenamiento no soportado: " + storageProvider);
        }
    }
}
