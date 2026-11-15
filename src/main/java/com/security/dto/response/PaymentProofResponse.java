package com.security.dto.response;

import com.security.enums.PaymentProofStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class PaymentProofResponse {
    private Long id;
    private Long orderId;
    private String orderNumber;
    private String originalFilename;
    private String contentType;
    private Long fileSizeBytes;
    private PaymentProofStatus status;
    private String referenceNumber;
    private String bankName;
    private BigDecimal amountDeclared;
    private LocalDate transferDate;
    private String notes;
    private LocalDateTime uploadedAt;
    private LocalDateTime reviewedAt;
    private String rejectionReason;
}
