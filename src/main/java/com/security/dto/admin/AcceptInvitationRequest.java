package com.security.dto.admin;

import com.security.validation.BcryptPassword;

import jakarta.validation.constraints.*;

/**
 * Request para que el empleado acepte su invitación y establezca su contraseña.
 * Este endpoint es público (no requiere JWT).
 */
public record AcceptInvitationRequest(
        @BcryptPassword
        @NotBlank(message = "La contraseña es obligatoria") @Size(min = 8, max = 100, message = "La contraseña debe tener entre 8 y 100 caracteres") @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#^()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).+$", message = "La contraseña debe contener al menos una mayúscula, una minúscula, un número y un carácter especial") String password,

        @NotBlank(message = "La confirmación de contraseña es obligatoria") String confirmPassword) {
}
