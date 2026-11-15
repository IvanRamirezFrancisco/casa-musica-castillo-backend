package com.security.dto.admin;

/**
 * DTO de solo lectura con los datos mínimos de un empleado activo
 * para el selector de destinatarios en las automatizaciones y alertas de stock.
 *
 * <p>
 * Solo se exponen campos no-sensibles (id, nombre, email, rol, isSuperAdmin).
 * La contraseña, tokens y demás campos privados nunca se incluyen.
 * </p>
 */
public record StaffRecipientDto(
                Long id,
                String fullName,
                String email,
                String role,
                boolean isSuperAdmin) {
}
