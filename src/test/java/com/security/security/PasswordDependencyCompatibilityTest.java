package com.security.security;

import com.security.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class PasswordDependencyCompatibilityTest {
    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();
    private static final String SYNTHETIC_PASSWORD = "Synthetic-only-passphrase!2026";
    // Generated only for this test with the previous Spring Security 6.2.0 encoder, strength 12.
    private static final String SYNTHETIC_LEGACY_HASH = "$2a$12$rm5j3kfum.1AA4wVTa5BI.rlxIwXqKRUt/8CoJRfW0tPeFJL0eiq.";

    @Test void normalPasswordsRetainStrengthTwelve() {
        String hash = encoder.encode(SYNTHETIC_PASSWORD);
        assertTrue(hash.startsWith("$2a$12$"));
        assertTrue(encoder.matches(SYNTHETIC_PASSWORD, hash));
    }
    @Test void incorrectPasswordIsRejected() {
        assertFalse(encoder.matches("DifferentSynthetic!2026", SYNTHETIC_LEGACY_HASH));
    }
    @Test void hashesCreatedBeforeTheUpgradeRemainValid() {
        assertTrue(encoder.matches(SYNTHETIC_PASSWORD, SYNTHETIC_LEGACY_HASH));
        assertFalse(encoder.upgradeEncoding(SYNTHETIC_LEGACY_HASH));
    }
    @Test void asciiBoundaryDoesNotAllowSuffixBypass() {
        String boundary = "x".repeat(72);
        String hash = encoder.encode(boundary);
        assertTrue(encoder.matches(boundary, hash));
        assertFalse(encoder.matches(boundary + "suffix", hash));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(boundary + "x"));
    }
    @Test void unicodeLimitIsMeasuredInUtf8Bytes() {
        String boundary = "\u00e9".repeat(36);
        String hash = encoder.encode(boundary);
        assertTrue(encoder.matches(boundary, hash));
        assertFalse(encoder.matches(boundary + "\u00e9", hash));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(boundary + "\u00e9"));
    }
    @Test void ordinaryMultibytePasswordStillMatches() {
        String password = "Synthetic\u00e9\u6f22!928";
        assertTrue(encoder.matches(password, encoder.encode(password)));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {71, 72, 73})
    void encodingAndMatchingUseTheSameByteBoundary(int bytes) {
        String password = "q".repeat(bytes);
        boolean allowed = bytes <= 72;
        assertEquals(allowed, com.security.validation.BcryptPasswordValidator.isWithinLimit(password));
        if (allowed) assertTrue(encoder.matches(password, encoder.encode(password)));
        else assertThrows(IllegalArgumentException.class, () -> encoder.encode(password));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {71, 72, 73})
    void multibytePasswordsUseBytesRatherThanCharacterCount(int bytes) {
        String password = "\u00e9".repeat(bytes / 2) + "x".repeat(bytes % 2);
        assertEquals(bytes, password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(bytes <= 72, new com.security.validation.BcryptPasswordValidator().isValid(password, null));
        if (bytes <= 72) assertTrue(encoder.matches(password, encoder.encode(password)));
        else {
            assertFalse(encoder.matches(password, encoder.encode("\u00e9".repeat(36))));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(password));
        }
    }
    @Test void byteConstraintLeavesPresenceToExistingDtoConstraints() {
        assertTrue(new com.security.validation.BcryptPasswordValidator().isValid(null, null));
    }
}
