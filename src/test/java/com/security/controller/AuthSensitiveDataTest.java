package com.security.controller;

import com.security.dto.request.LoginFactorRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AuthSensitiveDataTest {
    @Test void internalExceptionMessageAndFactorValuesAreNotLoggedOrReflected(CapturedOutput output) {
        String sensitive="SyntheticPassword_JWT_Challenge_Secret_Code_OnlyForTest";
        var response=new AuthExceptionHandler().internal(new IllegalStateException(sensitive));
        assertEquals(500,response.getStatusCode().value());
        assertFalse(response.getBody().getMessage().contains(sensitive));
        assertFalse(output.getAll().contains(sensitive));
        assertTrue(output.getAll().contains("IllegalStateException"));
        assertFalse(new LoginFactorRequest(sensitive,"EMAIL",sensitive).toString().contains(sensitive));
    }
}
