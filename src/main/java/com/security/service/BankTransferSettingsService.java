package com.security.service;

import com.security.dto.BankTransferSettingsRequest;
import com.security.dto.BankTransferSettingsResponse;
import com.security.dto.PaymentInstructionsResponse;
import com.security.entity.BankTransferSettings;
import com.security.entity.Order;
import com.security.enums.PaymentMethod;
import com.security.entity.User;
import com.security.exception.ResourceNotFoundException;
import com.security.repository.BankTransferSettingsRepository;
import com.security.repository.OrderRepository;
import com.security.repository.UserRepository;
import com.security.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BankTransferSettingsService {

    @Value("${bank.transfer.payment-validity-hours:72}")
    private int paymentValidityHours;

    private final BankTransferSettingsRepository bankTransferSettingsRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public BankTransferSettingsResponse getAdminSettings() {
        return bankTransferSettingsRepository.findByActiveTrue()
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResourceNotFoundException("No existe configuración bancaria activa."));
    }

    @Transactional
    public BankTransferSettingsResponse updateSettings(Long adminId, BankTransferSettingsRequest request) {
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin no encontrado"));

        // Normalización y validación de inputs
        String safeBankName = normalizeRequired(request.getBankName(), "El nombre del banco");
        String safeAccountHolder = normalizeRequired(request.getAccountHolder(), "El titular de la cuenta");
        String safeReference = normalizeOptional(request.getReferenceInstructions());
        String safeAdditional = normalizeOptional(request.getAdditionalInstructions());
        String safeAccountNumber = normalizeAndValidateAccountNumber(request.getAccountNumber());

        // Validacion manual adicional de seguridad para la CLABE
        if (!isValidClabe(request.getClabe())) {
            throw new IllegalArgumentException("La CLABE interbancaria no es válida.");
        }

        Optional<BankTransferSettings> currentOpt = bankTransferSettingsRepository.findByActiveTrue();
        BankTransferSettings settings;

        if (currentOpt.isPresent()) {
            settings = currentOpt.get();
            settings.setBankName(safeBankName);
            settings.setAccountHolder(safeAccountHolder);
            settings.setClabe(request.getClabe().trim());
            settings.setAccountNumber(safeAccountNumber);
            settings.setReferenceInstructions(safeReference);
            settings.setAdditionalInstructions(safeAdditional);
            settings.setUpdatedBy(admin);
        } else {
            settings = BankTransferSettings.builder()
                    .bankName(safeBankName)
                    .accountHolder(safeAccountHolder)
                    .clabe(request.getClabe().trim())
                    .accountNumber(safeAccountNumber)
                    .referenceInstructions(safeReference)
                    .additionalInstructions(safeAdditional)
                    .active(true)
                    .updatedBy(admin)
                    .build();
        }

        BankTransferSettings saved = bankTransferSettingsRepository.save(settings);
        
        auditLogService.logAction(
                "UPDATE_BANK_SETTINGS",
                "PAYMENT",
                adminId,
                "Actualización de configuración de transferencia bancaria"
        );
        
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public PaymentInstructionsResponse getPaymentInstructionsForOrder(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada"));

        // Validar ownership
        if (!order.getUser().getId().equals(userId)) {
            // Se usa el mismo mensaje genérico para no dar información a posibles atacantes
            throw new ResourceNotFoundException("Orden no encontrada o acceso denegado");
        }

        // Solo permitir si el método de pago es transferencia (BANK_TRANSFER)
        if (order.getPaymentMethod() != PaymentMethod.BANK_TRANSFER) {
            return PaymentInstructionsResponse.builder()
                    .configured(false)
                    .build();
        }

        Optional<BankTransferSettings> settingsOpt = bankTransferSettingsRepository.findByActiveTrue();

        if (settingsOpt.isEmpty()) {
            return PaymentInstructionsResponse.builder()
                    .configured(false)
                    .build();
        }

        BankTransferSettings settings = settingsOpt.get();

        LocalDateTime paymentDeadline = order.getCreatedAt().plusHours(paymentValidityHours);
        boolean isExpired = LocalDateTime.now().isAfter(paymentDeadline);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        return PaymentInstructionsResponse.builder()
                .configured(true)
                .bankName(settings.getBankName())
                .accountHolder(settings.getAccountHolder())
                .clabe(settings.getClabe())
                .accountNumber(settings.getAccountNumber())
                .concept(order.getOrderNumber())
                .amount(order.getTotal())
                .orderNumber(order.getOrderNumber())
                .referenceInstructions(settings.getReferenceInstructions())
                .additionalInstructions(settings.getAdditionalInstructions())
                .paymentDeadline(paymentDeadline.format(formatter))
                .isPaymentDeadlineExpired(isExpired)
                .build();
    }

    private BankTransferSettingsResponse mapToResponse(BankTransferSettings entity) {
        return BankTransferSettingsResponse.builder()
                .id(entity.getId())
                .bankName(entity.getBankName())
                .accountHolder(entity.getAccountHolder())
                .clabe(entity.getClabe())
                .accountNumber(entity.getAccountNumber())
                .referenceInstructions(entity.getReferenceInstructions())
                .additionalInstructions(entity.getAdditionalInstructions())
                .active(entity.isActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private boolean isValidClabe(String clabe) {
        if (clabe == null || clabe.trim().isEmpty()) return false;
        String cleanClabe = clabe.replaceAll("[^0-9]", "");
        if (cleanClabe.length() != 18) return false;
        
        int[] weights = {3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7};
        int totalSum = 0;
        for (int i = 0; i < 17; i++) {
            int digit = Character.getNumericValue(cleanClabe.charAt(i));
            totalSum += (digit * weights[i]) % 10;
        }
        int expectedCheckDigit = (10 - (totalSum % 10)) % 10;
        int actualCheckDigit = Character.getNumericValue(cleanClabe.charAt(17));
        return expectedCheckDigit == actualCheckDigit;
    }

    // --- Helpers de Normalización y Validación ---

    private String normalizeOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " es obligatorio");
        }
        return value.trim();
    }

    private String normalizeAndValidateAccountNumber(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) return null;
        if (!normalized.matches("^[0-9]+$")) {
            throw new IllegalArgumentException("El número de cuenta debe contener solo dígitos o dejarse vacío.");
        }
        return normalized;
    }
}
