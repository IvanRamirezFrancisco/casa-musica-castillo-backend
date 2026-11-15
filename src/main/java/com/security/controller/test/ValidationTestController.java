package com.security.controller.test;

import com.security.dto.request.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Endpoint de prueba para verificar validaciones.
 * Solo activo en perfiles local/dev. En producción Spring no crea el bean.
 */
@Profile({"local", "dev"})
@RestController
@RequestMapping("/api/test")
public class ValidationTestController {

    @Autowired
    private Validator validator;

    @GetMapping("/email-validation")
    public String testEmailValidation() {
        StringBuilder result = new StringBuilder();
        result.append("=== PRUEBA DE VALIDACIÓN DE EMAIL ===\n\n");

        // Email problemático que reportaste
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setFirstName("Juan");
        request.setLastName("García");
        request.setEmail("ivan@ivan.com"); // Este NO debe ser válido
        request.setPassword("SecurePass123!");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);

        boolean hasEmailViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("email"));

        if (hasEmailViolation) {
            result.append("✅ CORRECTO: 'ivan@ivan.com' es RECHAZADO\n");
            violations.stream()
                    .filter(v -> v.getPropertyPath().toString().equals("email"))
                    .forEach(v -> result.append("   - ").append(v.getMessage()).append("\n"));
        } else {
            result.append("❌ ERROR: 'ivan@ivan.com' NO fue rechazado\n");
            result.append("Este es el problema que reportaste!\n");
        }

        result.append("\n--- Probando email válido ---\n");

        // Email válido
        request.setEmail("test@gmail.com");
        violations = validator.validate(request);
        hasEmailViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("email"));

        if (!hasEmailViolation) {
            result.append("✅ CORRECTO: 'test@gmail.com' es ACEPTADO\n");
        } else {
            result.append("❌ ERROR: 'test@gmail.com' fue rechazado\n");
            violations.stream()
                    .filter(v -> v.getPropertyPath().toString().equals("email"))
                    .forEach(v -> result.append("   - ").append(v.getMessage()).append("\n"));
        }

        return result.toString();
    }

    @GetMapping("/name-validation")
    public String testNameValidation() {
        StringBuilder result = new StringBuilder();
        result.append("=== PRUEBA DE VALIDACIÓN DE NOMBRES ===\n\n");

        // Nombre válido
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setFirstName("María");
        request.setLastName("García");
        request.setEmail("test@gmail.com");
        request.setPassword("SecurePass123!");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        boolean hasNameViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("firstName"));

        if (!hasNameViolation) {
            result.append("✅ CORRECTO: 'María' es ACEPTADO\n");
        } else {
            result.append("❌ ERROR: 'María' fue rechazado\n");
        }

        result.append("\n--- Probando nombre con ataque ---\n");

        // Nombre con ataque
        request.setFirstName("John123");
        violations = validator.validate(request);
        hasNameViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("firstName"));

        if (hasNameViolation) {
            result.append("✅ CORRECTO: 'John123' es RECHAZADO\n");
        } else {
            result.append("❌ ERROR: 'John123' NO fue rechazado\n");
        }

        return result.toString();
    }

    @GetMapping("/password-validation")
    public String testPasswordValidation() {
        StringBuilder result = new StringBuilder();
        result.append("=== PRUEBA DE VALIDACIÓN DE CONTRASEÑAS ===\n\n");

        // Contraseña simple que debe ser rechazada
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setFirstName("Juan");
        request.setLastName("García");
        request.setEmail("test@gmail.com");
        request.setPassword("123456"); // Esta debe ser rechazada

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        boolean hasPasswordViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("password"));

        if (hasPasswordViolation) {
            result.append("✅ CORRECTO: '123456' es RECHAZADA\n");
            violations.stream()
                    .filter(v -> v.getPropertyPath().toString().equals("password"))
                    .limit(1)
                    .forEach(v -> result.append("   - ").append(v.getMessage()).append("\n"));
        } else {
            result.append("❌ ERROR: '123456' NO fue rechazada\n");
        }

        result.append("\n--- Probando contraseña válida ---\n");

        // Contraseña válida
        request.setPassword("SecurePass123!");
        violations = validator.validate(request);
        hasPasswordViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("password"));

        if (!hasPasswordViolation) {
            result.append("✅ CORRECTO: 'SecurePass123!' es ACEPTADA\n");
        } else {
            result.append("❌ ERROR: 'SecurePass123!' fue rechazada\n");
        }

        return result.toString();
    }
}