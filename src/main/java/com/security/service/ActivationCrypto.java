package com.security.service;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Domain-separated keys. Recoverable payload exists only until one dispatch attempt. */
@Component
public class ActivationCrypto {
    private final byte[] limitKey, payloadKey;
    private static final SecureRandom RANDOM = new SecureRandom();
    public ActivationCrypto(@Value("${app.security.jwt.secret}") String secret) {
        limitKey = mac(secret.getBytes(StandardCharsets.UTF_8), "cmc:activation-limits:v1");
        payloadKey = mac(secret.getBytes(StandardCharsets.UTF_8), "cmc:activation-outbox:v1");
    }
    public String subject(String subject) { return HexFormat.of().formatHex(mac(limitKey, subject)); }
    private static byte[] mac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) { throw new IllegalStateException("Activation cryptography unavailable"); }
    }
    public String seal(String value, String context) {
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, context).doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) { throw new IllegalStateException("Cannot protect activation delivery"); }
    }
    public String open(String value, String context) {
        try {
            String[] parts = value.split(":", 2);
            return new String(cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(parts[0]), context)
                .doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Cannot recover activation delivery"); }
    }
    private Cipher cipher(int mode, byte[] nonce, String context) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(payloadKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
