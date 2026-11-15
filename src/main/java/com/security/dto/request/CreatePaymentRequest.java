package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO de solicitud para crear un intento de pago desde el cliente.
 *
 * Solo acepta el campo "provider". Todos los demás campos (amount, status,
 * currency, providerPaymentId, checkoutUrl, etc.) son rechazados: el backend
 * los genera de forma segura desde la orden y la lógica de negocio.
 *
 * En Fase 7A el único provider aceptado para clientes es BANK_TRANSFER.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentRequest {

    @NotBlank(message = "El proveedor de pago es obligatorio")
    private String provider;
}
