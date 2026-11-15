package com.security.validation;

import com.security.util.PasswordValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validador personalizado para contraseñas seguras.
 * Rechaza patrones simples, secuencias y contraseñas comunes.
 */
@Component
public class SecurePasswordValidator implements ConstraintValidator<SecurePassword, String> {

    @Autowired(required = false)
    private PasswordValidator passwordValidator;

    // Patrones simples que deben ser rechazados si aparecen como subcadena
    private static final Set<String> FORBIDDEN_PATTERNS = Set.of(
            "123456", "1234567", "12345678", "123456789", "1234567890",
            "password", "password123", "admin123", "qwerty", "qwerty123",
            "abc123", "qwertyuiop", "asdfghjkl", "zxcvbnm",
            "111111", "000000", "123123", "321321", "654321",
            "aaaaaa", "bbbbbb", "cccccc", "abcdef", "fedcba");

    private static final Set<String> FORBIDDEN_EXACT_PASSWORDS = Set.of(
            "admin", "root", "user", "guest", "demo", "asdf", "zxcv");

    // Patrones de secuencias a rechazar (4+ caracteres para evitar falsos positivos en palabras como "Fuerte" o "Estudio")
    private static final Pattern[] SEQUENCE_PATTERNS = {
            Pattern.compile("(.)\\1{2,}"), // 3+ caracteres repetidos idénticos
            Pattern.compile("(0123|1234|2345|3456|4567|5678|6789|7890|9876|8765|7654|6543|5432|4321|3210)"), // Secuencias numéricas
            Pattern.compile(
                    "(abcd|bcde|cdef|defg|efgh|fghi|ghij|hijk|ijkl|jklm|klmn|lmno|mnop|nopq|opqr|pqrs|qrst|rstu|stuv|tuvw|uvwx|vwxy|wxyz)"), // Secuencias alfabéticas
            Pattern.compile("(qwer|wert|erty|rtyu|tyui|yuio|uiop)"), // Secuencias de teclado fila superior
            Pattern.compile("(asdf|sdfg|dfgh|fghj|ghjk|hjkl)"), // Secuencias de teclado fila media
            Pattern.compile("(zxcv|xcvb|cvbn|vbnm)") // Secuencias de teclado fila inferior
    };

    @Override
    public void initialize(SecurePassword constraintAnnotation) {
        // Inicialización si es necesaria
    }

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return false;
        }

        if (!BcryptPasswordValidator.isWithinLimit(password)) {
            updateErrorMessage(context, "La contraseña no debe superar 72 bytes UTF-8");
            return false;
        }

        if (password.chars().anyMatch(Character::isWhitespace)) {
            updateErrorMessage(context, "La contraseña no debe contener espacios en blanco");
            return false;
        }

        String passwordLower = password.toLowerCase();

        if (FORBIDDEN_EXACT_PASSWORDS.contains(passwordLower)) {
            updateErrorMessage(context, "La contraseña seleccionada es demasiado común");
            return false;
        }

        // Verificar patrones prohibidos
        for (String forbidden : FORBIDDEN_PATTERNS) {
            if (passwordLower.contains(forbidden)) {
                updateErrorMessage(context, "La contraseña contiene un patrón simple prohibido");
                return false;
            }
        }

        // Verificar secuencias
        for (Pattern pattern : SEQUENCE_PATTERNS) {
            if (pattern.matcher(passwordLower).find()) {
                updateErrorMessage(context,
                        "La contraseña contiene secuencias simples o caracteres repetidos consecutivos");
                return false;
            }
        }

        // Verificar contraseña muy corta
        if (password.length() < 8) {
            updateErrorMessage(context, "La contraseña debe tener al menos 8 caracteres");
            return false;
        }

        // Verificar que tenga variedad de caracteres (mayúscula, minúscula, número y símbolo)
        boolean hasLower = password.matches(".*[a-z].*");
        boolean hasUpper = password.matches(".*[A-Z].*");
        boolean hasDigit = password.matches(".*\\d.*");
        boolean hasSpecial = password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");

        int complexity = 0;
        if (hasLower)
            complexity++;
        if (hasUpper)
            complexity++;
        if (hasDigit)
            complexity++;
        if (hasSpecial)
            complexity++;

        if (complexity < 4) {
            updateErrorMessage(context,
                    "La contraseña debe incluir al menos una mayúscula, una minúscula, un número y un carácter especial");
            return false;
        }

        // Usar el validador original para verificaciones adicionales si está disponible
        if (passwordValidator != null) {
            try {
                PasswordValidator.ValidationResult result = passwordValidator.validatePassword(password);

                if (!result.isValid()) {
                    updateErrorMessage(context, "Contraseña inválida: " + result.getMessagesAsString());
                    return false;
                }
            } catch (Exception e) {
                updateErrorMessage(context, "La contraseña no cumple los requisitos de seguridad");
                return false;
            }
        }

        return true;
    }

    private void updateErrorMessage(ConstraintValidatorContext context, String message) {
        if (context != null) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        }
    }
}
