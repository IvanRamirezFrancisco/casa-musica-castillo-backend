package com.security.service;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpSecretStorageTest {
    @Test
    void rolloutReadsBothFormatsAndUsesAuthenticatedEncryptionPerAccount() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encodedKey = Base64.getEncoder().encodeToString(key);
        var writeEncrypted = new TotpSecretStorage(encodedKey, true);
        var dualReader = new TotpSecretStorage(encodedKey, false);
        String legacy = "JBSWY3DPEHPK3PXP";

        assertThat(dualReader.encode(7, legacy)).isEqualTo(legacy);
        assertThat(writeEncrypted.decode(7, legacy)).isEqualTo(legacy);

        String encrypted = writeEncrypted.encode(7, legacy);
        assertThat(encrypted).startsWith("v1:").doesNotContain(legacy);
        assertThat(writeEncrypted.encode(7, legacy)).isNotEqualTo(encrypted);
        assertThat(dualReader.decode(7, encrypted)).isEqualTo(legacy);
        assertThatThrownBy(() -> dualReader.decode(8, encrypted)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TotpSecretStorage("", false).decode(7, encrypted))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TotpSecretStorage("", true)).isInstanceOf(IllegalArgumentException.class);
    }
}
