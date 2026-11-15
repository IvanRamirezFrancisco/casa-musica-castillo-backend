package com.security.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ClabeValidator implements ConstraintValidator<ValidClabe, String> {

    private static final int[] WEIGHTS = {3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7};

    @Override
    public boolean isValid(String clabe, ConstraintValidatorContext context) {
        if (clabe == null || clabe.trim().isEmpty()) {
            return false;
        }

        // Remover espacios o guiones (aunque en backend ya deberia llegar limpia si se limpia en frontend)
        String cleanClabe = clabe.replaceAll("[^0-9]", "");

        // Debe tener exactamente 18 digitos
        if (cleanClabe.length() != 18) {
            return false;
        }

        // Validar digito verificador (modulo 10)
        try {
            int totalSum = 0;
            for (int i = 0; i < 17; i++) {
                int digit = Character.getNumericValue(cleanClabe.charAt(i));
                int sum = (digit * WEIGHTS[i]) % 10;
                totalSum += sum;
            }

            int expectedCheckDigit = (10 - (totalSum % 10)) % 10;
            int actualCheckDigit = Character.getNumericValue(cleanClabe.charAt(17));

            return expectedCheckDigit == actualCheckDigit;
        } catch (Exception e) {
            return false;
        }
    }
}
