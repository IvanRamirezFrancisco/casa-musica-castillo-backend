package com.security.controller;

import com.security.dto.response.ApiResponse;
import com.security.exception.AuthFlowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Never include rejected input, parser messages, SQL values or token-bearing paths. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AuthController.class, TwoFactorController.class,
        PasswordResetController.class, SecurePasswordResetController.class, AcceptInvitationController.class})
public class AuthExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);
    @ExceptionHandler(AuthFlowException.class)
    public ResponseEntity<ApiResponse> flow(AuthFlowException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiResponse(false, ex.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse> invalid(Exception ex) {
        log.warn("Solicitud auth rechazada: {}", ex.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(new ApiResponse(false, "Datos de autenticación inválidos"));
    }
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse> unauthenticated(AuthenticationException ex) {
        return ResponseEntity.status(401).body(new ApiResponse(false, "Credenciales inválidas"));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse> denied(Exception ex) {
        return ResponseEntity.status(403).body(new ApiResponse(false, "Acceso denegado"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> internal(Exception ex) {
        String id = java.util.UUID.randomUUID().toString();
        log.error("Fallo auth id={} tipo={}", id, ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new ApiResponse(false, "Error de autenticación. Referencia: " + id));
    }
}
