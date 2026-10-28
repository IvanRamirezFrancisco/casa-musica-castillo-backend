package com.security.exception;

import org.springframework.http.HttpStatus;

public class AuthFlowException extends RuntimeException {
    private final HttpStatus status;

    public AuthFlowException(HttpStatus status) {
        super(switch (status) {
            case TOO_MANY_REQUESTS -> "Demasiados intentos. Inicia sesión más tarde.";
            case SERVICE_UNAVAILABLE -> "No se pudo confirmar el envío. Espera antes de reintentar.";
            case FORBIDDEN -> "Operación no permitida.";
            default -> "Autenticación inválida. Inicia sesión nuevamente.";
        });
        this.status = status;
    }

    public AuthFlowException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
