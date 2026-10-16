package com.security.controller;

import com.security.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.TransactionException;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;

/**
 * Manejador global de excepciones para toda la aplicación
 * Proporciona respuestas consistentes para diferentes tipos de errores
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

        /**
         * Maneja BadRequestException (solicitudes inválidas).
         * HTTP 400 - BAD REQUEST
         *
         * <p>
         * Usada por el módulo de analytics de clustering y otros servicios
         * para rechazar parámetros de entrada inválidos (cluster negativo,
         * paginación fuera de rango, etc.).
         * </p>
         */
        @ExceptionHandler(BadRequestException.class)
        public ResponseEntity<ErrorResponse> handleBadRequestException(
                        BadRequestException ex, WebRequest request) {

                log.warn("Solicitud inválida: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.BAD_REQUEST.value())
                                .error("Bad Request")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        /**
         * Maneja excepciones de carrito no encontrado
         * HTTP 404 - NOT FOUND
         */
        @ExceptionHandler(CartNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleCartNotFoundException(
                        CartNotFoundException ex, WebRequest request) {

                log.error("Carrito no encontrado: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.NOT_FOUND.value())
                                .error("Not Found")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
        }

        /**
         * Maneja excepciones de stock insuficiente
         * HTTP 409 - CONFLICT
         */
        @ExceptionHandler(InsufficientStockException.class)
        public ResponseEntity<ErrorResponse> handleInsufficientStockException(
                        InsufficientStockException ex, WebRequest request) {

                log.error("Stock insuficiente - Producto: {}, Solicitado: {}, Disponible: {}",
                                ex.getProductId(), ex.getRequested(), ex.getAvailable());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.CONFLICT.value())
                                .error("Conflict")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .details(Map.of(
                                                "productId", ex.getProductId(),
                                                "requested", ex.getRequested(),
                                                "available", ex.getAvailable()))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.CONFLICT);
        }

        /**
         * Maneja excepciones de cupón inválido
         * HTTP 400 - BAD REQUEST
         */
        @ExceptionHandler(InvalidCouponException.class)
        public ResponseEntity<ErrorResponse> handleInvalidCouponException(
                        InvalidCouponException ex, WebRequest request) {

                log.error("Cupón inválido - Código: {}, Mensaje: {}",
                                ex.getCouponCode(), ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.BAD_REQUEST.value())
                                .error("Bad Request")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .details(ex.getCouponCode() != null ? Map.of("couponCode", ex.getCouponCode())
                                                : Map.of())
                                .build();

                return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        /**
         * Maneja excepciones de acción duplicada
         * HTTP 409 - CONFLICT
         */
        @ExceptionHandler(DuplicateActionException.class)
        public ResponseEntity<ErrorResponse> handleDuplicateActionException(
                        DuplicateActionException ex, WebRequest request) {

                log.error("Acción duplicada: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.CONFLICT.value())
                                .error("Conflict")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.CONFLICT);
        }

        /**
         * Maneja excepciones de acceso no autorizado
         * HTTP 403 - FORBIDDEN
         */
        @ExceptionHandler(UnauthorizedException.class)
        public ResponseEntity<ErrorResponse> handleUnauthorizedException(
                        UnauthorizedException ex, WebRequest request) {

                log.error("Acceso no autorizado: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.FORBIDDEN.value())
                                .error("Forbidden")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
        }

        /**
         * Maneja excepciones de recurso no encontrado (genérica)
         * HTTP 404 - NOT FOUND
         */
        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
                        ResourceNotFoundException ex, WebRequest request) {

                log.error("Recurso no encontrado: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.NOT_FOUND.value())
                                .error("Not Found")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .details(Map.of(
                                                "resourceName", ex.getResourceName(),
                                                "fieldName", ex.getFieldName(),
                                                "fieldValue", ex.getFieldValue().toString()))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
        }

        /**
         * Maneja excepciones de violación de seguridad (SecurityViolationException)
         * HTTP 403 - FORBIDDEN
         * Ejemplo: Admin intentando desactivarse/bloquearse a sí mismo o quitarse el
         * rol ADMIN
         */
        @ExceptionHandler(SecurityViolationException.class)
        public ResponseEntity<ErrorResponse> handleSecurityViolationException(
                        SecurityViolationException ex, WebRequest request) {

                log.error("⚠️ Violación de seguridad: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.FORBIDDEN.value())
                                .error("Security Violation")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .details(Map.of("type", "SECURITY_VIOLATION"))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
        }

        /**
         * Maneja excepciones de violación de jerarquía de roles o del Protected Owner.
         * HTTP 403 - FORBIDDEN
         */
        @ExceptionHandler(com.security.exception.SecurityHierarchyException.class)
        public ResponseEntity<ErrorResponse> handleSecurityHierarchyException(
                        com.security.exception.SecurityHierarchyException ex, WebRequest request) {

                log.warn("🛡️ Jerarquía de seguridad violada: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.FORBIDDEN.value())
                                .error("Hierarchy Violation")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .details(Map.of("type", "SECURITY_HIERARCHY_VIOLATION"))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
        }

        /**
         * Maneja errores de validación de datos (jakarta.validation)
         * HTTP 400 - BAD REQUEST
         */
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorResponse> handleValidationException(
                        MethodArgumentNotValidException ex, WebRequest request) {

                log.error("Error de validación: {}", ex.getMessage());

                Map<String, String> validationErrors = new HashMap<>();
                ex.getBindingResult().getAllErrors().forEach(error -> {
                        String fieldName = ((FieldError) error).getField();
                        String errorMessage = error.getDefaultMessage();
                        validationErrors.put(fieldName, errorMessage);
                });

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.BAD_REQUEST.value())
                                .error("Validation Failed")
                                .message("Los datos proporcionados no son válidos")
                                .path(request.getDescription(false).replace("uri=", ""))
                                .details(Map.of("validationErrors", validationErrors))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        /**
         * Maneja errores de tipo de argumento incorrecto
         * HTTP 400 - BAD REQUEST
         */
        @ExceptionHandler(MethodArgumentTypeMismatchException.class)
        public ResponseEntity<ErrorResponse> handleTypeMismatchException(
                        MethodArgumentTypeMismatchException ex, WebRequest request) {

                log.error("Error de tipo de argumento: {}", ex.getMessage());

                String message = String.format("El parámetro '%s' tiene un valor inválido '%s'",
                                ex.getName(), ex.getValue());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.BAD_REQUEST.value())
                                .error("Bad Request")
                                .message(message)
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        /**
         * Maneja excepciones de estado ilegal
         * HTTP 400 - BAD REQUEST
         */
        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<ErrorResponse> handleIllegalStateException(
                        IllegalStateException ex, WebRequest request) {

                log.error("Estado ilegal: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.BAD_REQUEST.value())
                                .error("Bad Request")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        /**
         * Maneja excepciones de autenticación (credenciales incorrectas)
         * HTTP 401 - UNAUTHORIZED
         */
        @ExceptionHandler({ AuthenticationException.class, BadCredentialsException.class })
        public ResponseEntity<ErrorResponse> handleAuthenticationException(
                        Exception ex, WebRequest request) {

                log.error("Error de autenticación: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.UNAUTHORIZED.value())
                                .error("Unauthorized")
                                .message("Credenciales incorrectas o token inválido")
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
        }

        /**
         * Maneja excepciones de acceso denegado (sin permisos)
         * HTTP 403 - FORBIDDEN
         * Hermético: no expone la razón exacta de la denegación
         */
        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<ErrorResponse> handleAccessDeniedException(
                        AccessDeniedException ex, WebRequest request) {

                String errorId = UUID.randomUUID().toString();
                log.error("[errorId={}] Acceso denegado: {} | path: {}", errorId, ex.getMessage(),
                                request.getDescription(false));

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.FORBIDDEN.value())
                                .error("Forbidden")
                                .message("No tienes permisos para acceder a este recurso")
                                .path(request.getDescription(false).replace("uri=", ""))
                                .errorId(errorId)
                                .build();

                return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
        }

        /**
         * Maneja archivos CSV que superan el límite de tamaño configurado en Spring
         * HTTP 413 - PAYLOAD TOO LARGE
         */
        @ExceptionHandler(MaxUploadSizeExceededException.class)
        public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceededException(
                        MaxUploadSizeExceededException ex, WebRequest request) {

                log.warn("Archivo CSV demasiado grande: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.PAYLOAD_TOO_LARGE.value())
                                .error("Payload Too Large")
                                .message("El archivo supera el tamaño máximo permitido (5 MB). Divide el CSV en partes más pequeñas.")
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.PAYLOAD_TOO_LARGE);
        }

        /**
         * Maneja excepciones de argumento ilegal
         * HTTP 400 - BAD REQUEST
         */
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
                        IllegalArgumentException ex, WebRequest request) {

                log.error("Argumento ilegal: {}", ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.BAD_REQUEST.value())
                                .error("Bad Request")
                                .message(ex.getMessage())
                                .path(request.getDescription(false).replace("uri=", ""))
                                .build();

                return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        /**
         * Maneja violaciones de restricciones de BD (campos únicos, NOT NULL, etc.)
         * HTTP 409 - CONFLICT
         * Hermético: NO expone detalles de la causa de BD; se loguea server-side con
         * errorId
         */
        @ExceptionHandler(DataIntegrityViolationException.class)
        public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
                        DataIntegrityViolationException ex, WebRequest request) {

                String errorId = UUID.randomUUID().toString();
                String cause = ex.getMostSpecificCause().getMessage();
                log.error("[errorId={}] Violación de integridad de datos: {}", errorId, cause);

                // Mensaje genérico — no filtramos por contenido para evitar fugas
                String userMessage = "Conflicto de datos. Si el problema persiste, contacta al soporte con el código: "
                                + errorId;

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.CONFLICT.value())
                                .error("Conflict")
                                .message(userMessage)
                                .path(request.getDescription(false).replace("uri=", ""))
                                .errorId(errorId)
                                .build();

                return new ResponseEntity<>(error, HttpStatus.CONFLICT);
        }

        /**
         * Maneja rutas no encontradas (404)
         * Evita que se dispare el manejador global de Exception (500)
         */
        @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
        public ResponseEntity<ErrorResponse> handleNotFound(
                        Exception ex, WebRequest request) {

                String errorId = UUID.randomUUID().toString();
                log.warn("[errorId={}] Recurso no encontrado en path={}: {}",
                                errorId, request.getDescription(false), ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.NOT_FOUND.value())
                                .error("Not Found")
                                .message("Recurso no encontrado")
                                .path(request.getDescription(false).replace("uri=", ""))
                                .errorId(errorId)
                                .build();

                return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
        }

        /**
         * Maneja excepciones de Base de Datos genéricas para evitar fugas de SQL
         */
        @ExceptionHandler({SQLException.class, DataAccessException.class})
        public ResponseEntity<ErrorResponse> handleDatabaseExceptions(
                        Exception ex, WebRequest request) {

                String errorId = UUID.randomUUID().toString();
                log.error("[errorId={}] Error de Base de Datos en path={}: {}",
                                errorId, request.getDescription(false), ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                                .error("Database Error")
                                .message("Ocurrió un error en la base de datos. Inténtalo nuevamente. Código: " + errorId)
                                .path(request.getDescription(false).replace("uri=", ""))
                                .errorId(errorId)
                                .build();

                return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        /**
         * Maneja excepciones de Transacciones (e.g. TransactionSystemException, RollbackException)
         */
        @ExceptionHandler(TransactionException.class)
        public ResponseEntity<ErrorResponse> handleTransactionSystemException(
                        TransactionException ex, WebRequest request) {

                String errorId = UUID.randomUUID().toString();
                log.error("[errorId={}] Error de Transacción en path={}: {}",
                                errorId, request.getDescription(false), ex.getMessage());

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                                .error("Transaction Error")
                                .message("No se pudo completar la operación transaccional. Inténtalo nuevamente. Código: " + errorId)
                                .path(request.getDescription(false).replace("uri=", ""))
                                .errorId(errorId)
                                .build();

                return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        /**
         * Maneja todas las excepciones no específicas
         * HTTP 500 - INTERNAL SERVER ERROR
         * Hermético: loguea stack trace completo server-side con errorId, devuelve
         * solo mensaje genérico + errorId al cliente.
         */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorResponse> handleGlobalException(
                        Exception ex, WebRequest request) {

                String errorId = UUID.randomUUID().toString();
                log.error("[errorId={}] Error interno del servidor en path={}: {}",
                                errorId, request.getDescription(false), ex.getMessage(), ex);

                ErrorResponse error = ErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                                .error("Internal Server Error")
                                .message("Ha ocurrido un error inesperado. Código de referencia: " + errorId)
                                .path(request.getDescription(false).replace("uri=", ""))
                                .errorId(errorId)
                                .build();

                return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        /**
         * Maneja validaciones fallidas de Pickup
         * HTTP 400 - BAD REQUEST
         */
        @ExceptionHandler(PickupValidationException.class)
        public ResponseEntity<Map<String, Object>> handlePickupValidationException(
                        PickupValidationException ex, WebRequest request) {
                
                log.warn("Error de validación pickup: {}", ex.getMessage());
                
                Map<String, Object> response = new HashMap<>();
                response.put("error", "INVALID_PICKUP_AUTHORIZATIONS");
                response.put("message", ex.getMessage());
                response.put("details", ex.getDetails());
                
                return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

        /**
         * Clase interna para representar respuestas de error
         */
        @lombok.Data
        @lombok.Builder
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class ErrorResponse {
                private LocalDateTime timestamp;
                private Integer status;
                private String error;
                private String message;
                private String path;
                private String errorId;
                private Map<String, Object> details;
        }
}
