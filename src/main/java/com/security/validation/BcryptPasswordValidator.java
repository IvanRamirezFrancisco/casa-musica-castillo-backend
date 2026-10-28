package com.security.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class BcryptPasswordValidator implements ConstraintValidator<BcryptPassword, CharSequence> {
    public static final int MAX_BYTES = 72;

    public static boolean isWithinLimit(CharSequence value) {
        return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return isWithinLimit(value);
    }
}
