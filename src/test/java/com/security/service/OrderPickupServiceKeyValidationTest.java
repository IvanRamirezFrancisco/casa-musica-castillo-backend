package com.security.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class OrderPickupServiceKeyValidationTest {

    private OrderPickupService serviceWithKey(String key) {
        OrderPickupService service = new OrderPickupService();
        ReflectionTestUtils.setField(service, "encryptionKey", key);
        return service;
    }

    private void validate(OrderPickupService service) {
        ReflectionTestUtils.invokeMethod(service, "validateEncryptionKey");
    }

    @Test
    void acceptsAesSizedKeys() {
        assertDoesNotThrow(() -> validate(serviceWithKey("a".repeat(16))));
        assertDoesNotThrow(() -> validate(serviceWithKey("a".repeat(24))));
        assertDoesNotThrow(() -> validate(serviceWithKey("a".repeat(32))));
    }

    @Test
    void rejectsMissingKeyWithClearMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validate(serviceWithKey(" ")));
        assertTrue(ex.getMessage().contains("PICKUP_CODE_ENCRYPTION_KEY"));
    }

    @Test
    void rejectsWrongSizeWithClearMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validate(serviceWithKey("short")));
        assertTrue(ex.getMessage().contains("16, 24 o 32"));
    }

    @Test
    void encryptDecryptRoundTrip() {
        OrderPickupService service = serviceWithKey("b".repeat(32));
        String encrypted = ReflectionTestUtils.invokeMethod(service, "encrypt", "ABC123");
        assertNotEquals("ABC123", encrypted);
        assertEquals("ABC123", service.decryptPickupCode(encrypted));
    }
}
