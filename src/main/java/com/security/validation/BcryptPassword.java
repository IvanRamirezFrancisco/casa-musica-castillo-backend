package com.security.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Byte limit for new BCrypt credentials; presence/strength remain separate constraints. */
@Documented
@Constraint(validatedBy = BcryptPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BcryptPassword {
    String message() default "La contraseña no debe superar 72 bytes UTF-8";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
