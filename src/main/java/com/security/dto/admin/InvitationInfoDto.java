package com.security.dto.admin;

import java.util.List;

/**
 * DTO devuelto al validar un token de invitación (endpoint público).
 * Contiene solo información no sensible para pre-poblar el formulario.
 */
public record InvitationInfoDto(
        String firstName,
        String lastName,
        String email,
        List<String> roleNames) {
}
