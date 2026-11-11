package com.security.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class TotpSecretStorage {
    private static final String VERSION = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final boolean encryptWrites;

    TotpSecretStorage(@Value("${app.security.totp.storage-key:}") String base64Key,
                      @Value("${app.security.totp.encrypt-new-secrets:false}") boolean encryptWrites) {
        this.encryptWrites = encryptWrites;
        this.key = base64Key.isBlank() ? null : parseKey(base64Key);
        if (encryptWrites && key == null) {
            throw new IllegalArgumentException("Falta la clave de almacenamiento TOTP.");
        }
    }

    String encode(long userId, String secret) {
        if (!encryptWrites) {
            return secret;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, userId, nonce)
                    .doFinal(secret.getBytes(StandardCharsets.US_ASCII));
            return VERSION + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo proteger la configuración TOTP.");
        }
    }

    String decode(long userId, String stored) {
        if (stored == null || !stored.contains(":")) {
            return stored;
        }
        if (!stored.startsWith(VERSION) || key == null) {
            throw new IllegalStateException("No se puede leer la configuración TOTP almacenada.");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(stored.substring(VERSION.length()));
            if (payload.length <= NONCE_BYTES + TAG_BITS / Byte.SIZE) {
                throw new IllegalArgumentException();
            }
            return new String(cipher(Cipher.DECRYPT_MODE, userId, Arrays.copyOf(payload, NONCE_BYTES))
                    .doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("No se pudo verificar la configuración TOTP almacenada.");
        }
    }

    private Cipher cipher(int mode, long userId, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(userId).array());
        return cipher;
    }

    private static SecretKeySpec parseKey(String base64Key) {
        byte[] bytes = Base64.getDecoder().decode(base64Key);
        try {
            if (bytes.length != 32) {
                throw new IllegalArgumentException("La clave de almacenamiento TOTP debe tener 256 bits.");
            }
            return new SecretKeySpec(bytes, "AES");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
