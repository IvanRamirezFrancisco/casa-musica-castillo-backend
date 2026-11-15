package com.security.dto.response;

import org.springframework.core.io.Resource;

public record PaymentProofFileResponse(
        Resource resource,
        String contentType,
        String originalFilename,
        String orderNumber
) {}
