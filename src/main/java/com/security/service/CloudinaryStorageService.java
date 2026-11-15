package com.security.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.security.dto.StorageUploadResult;
import com.security.util.ImageValidator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public class CloudinaryStorageService implements StorageService {

    private final Cloudinary cloudinary;
    private final ImageValidator imageValidator;

    public CloudinaryStorageService(
            ImageValidator imageValidator,
            String cloudName,
            String apiKey,
            String apiSecret) {
        
        this.imageValidator = imageValidator;
        
        if (!StringUtils.hasText(cloudName) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(apiSecret)) {
            throw new IllegalStateException("Cloudinary credentials are not properly configured.");
        }
        
        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret,
                "secure", true
        ));
    }

    @Override
    public StorageUploadResult uploadProductImage(MultipartFile file, Long productId, String folderPath) throws IOException {
        validateImageFile(file);
        return uploadToCloudinary(file, folderPath, "image", null);
    }

    @Override
    public StorageUploadResult uploadBrandImage(MultipartFile file, Long brandId, String folderPath) throws IOException {
        validateImageFile(file);
        return uploadToCloudinary(file, folderPath, "image", null);
    }

    @Override
    public StorageUploadResult uploadFile(MultipartFile file, String folderPath) throws IOException {
        // Validamos el tamaño, pero no que sea estrictamente una imagen (puede ser PDF)
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("El archivo excede el tamaño máximo permitido de 10MB");
        }
        return uploadToCloudinary(file, folderPath, "auto", null);
    }

    @Override
    public StorageUploadResult uploadFileWithCustomName(MultipartFile file, String folderPath, String customName, String resourceType) throws IOException {
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("El archivo excede el tamaño máximo permitido de 10MB");
        }
        return uploadToCloudinary(file, folderPath, resourceType, customName);
    }

    @Override
    public void delete(String publicIdOrPath) throws IOException {
        if (publicIdOrPath == null || publicIdOrPath.trim().isEmpty()) {
            return;
        }
        cloudinary.uploader().destroy(publicIdOrPath, ObjectUtils.emptyMap());
    }

    @Override
    public String getProviderName() {
        return "CLOUDINARY";
    }

    @Override
    public void validateImageFile(MultipartFile file) {
        imageValidator.validateImage(file);
    }

    private StorageUploadResult uploadToCloudinary(MultipartFile file, String folder, String resourceType, String customName) throws IOException {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "file");
        String extension = getExtension(originalFilename);
        
        String publicId = (customName != null && !customName.isBlank()) ? customName : UUID.randomUUID().toString();

        Map<String, Object> params = ObjectUtils.asMap(
                "folder", folder,
                "public_id", publicId,
                "resource_type", resourceType
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> uploadResult = cloudinary.uploader().upload(file.getBytes(), params);

        return StorageUploadResult.builder()
                .url(uploadResult.get("url").toString())
                .secureUrl(uploadResult.get("secure_url").toString())
                .publicId(uploadResult.get("public_id").toString())
                .originalFilename(originalFilename)
                .storedFilename(uploadResult.get("public_id").toString() + "." + extension)
                .contentType(file.getContentType())
                .fileSizeBytes(file.getSize())
                .provider(getProviderName())
                .resourceType(uploadResult.get("resource_type") != null ? uploadResult.get("resource_type").toString() : resourceType)
                .folder(uploadResult.get("folder") != null ? uploadResult.get("folder").toString() : folder)
                .format(uploadResult.get("format") != null ? uploadResult.get("format").toString() : extension)
                .build();
    }

    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1) {
            return filename.substring(dotIndex + 1);
        }
        return "jpg"; // fallback
    }
}
