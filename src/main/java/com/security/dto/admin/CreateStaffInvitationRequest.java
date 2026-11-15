package com.security.dto.admin;

import jakarta.validation.constraints.*;
import java.util.List;

/**
 * Request para crear una invitación de empleado.
 * Solo requiere nombre, apellido, email y roles.
 * NO requiere contraseña — el empleado la define al aceptar.
 */
public record CreateStaffInvitationRequest(
        @NotBlank(message = "El correo es obligatorio") @Email(message = "Formato de correo inválido") @Size(max = 100, message = "El correo no puede exceder 100 caracteres") String email,

        @NotBlank(message = "El nombre es obligatorio") @Size(min = 2, max = 50, message = "El nombre debe tener entre 2 y 50 caracteres") String firstName,

        @NotBlank(message = "El apellido es obligatorio") @Size(min = 2, max = 50, message = "El apellido debe tener entre 2 y 50 caracteres") String lastName,

        @NotEmpty(message = "Debe seleccionar al menos un rol") @Size(max = 5, message = "No se pueden asignar más de 5 roles") List<Long> roleIds) {
}
