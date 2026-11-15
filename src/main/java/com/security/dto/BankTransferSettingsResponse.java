package com.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankTransferSettingsResponse {

    private Long id;
    private String bankName;
    private String accountHolder;
    private String clabe;
    private String accountNumber;
    private String referenceInstructions;
    private String additionalInstructions;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
