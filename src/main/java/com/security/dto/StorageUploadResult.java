package com.security.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StorageUploadResult {
    private String url;
    private String secureUrl;
    private String publicId;
    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private Long fileSizeBytes;
    private String provider;
    private String resourceType;
    private String folder;
    private String format;
}
