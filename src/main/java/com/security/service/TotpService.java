package com.security.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class TotpService {
    private static final Logger log = LoggerFactory.getLogger(TotpService.class);

    @Value("${app.security.two-factor.issuer:AuthSystem}")
    private String issuer;

    private final GoogleAuthenticator gAuth;
    private final java.time.Clock clock;

    public TotpService() { this(java.time.Clock.systemUTC()); }

    TotpService(java.time.Clock clock) {
        this.clock = clock;
        this.gAuth = new GoogleAuthenticator();
        log.debug("GoogleAuthenticator inicializado con configuración estándar (ventana 30s)");
    }

    /**
     * Genera un secret key usando GoogleAuthenticator.
     * Este será el único secret que se use en toda la aplicación.
     */
    public String generateSecretKey() {
        GoogleAuthenticatorKey key = gAuth.createCredentials();
        String secret = key.getKey();

        // Validar que el secret sea usable inmediatamente
        try {
            gAuth.getTotpPassword(secret);
            log.debug("Secret TOTP generado y validado correctamente");
        } catch (Exception e) {
            log.error("Secret TOTP generado pero inválido: {}", e.getClass().getSimpleName());
            throw new RuntimeException("Secret generado inválido", e);
        }

        return secret;
    }

    /**
     * Genera la URL otpauth:// con el formato exacto requerido por Google Authenticator.
     */
    public String generateOtpAuthUrl(String secret, String email) {
        try {
            String accountName = URLEncoder.encode(email, StandardCharsets.UTF_8);
            String issuerEncoded = URLEncoder.encode(issuer, StandardCharsets.UTF_8);

            String otpAuthUrl = String.format(
                    "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
                    issuerEncoded,
                    accountName,
                    secret,
                    issuerEncoded);

            log.debug("URL otpauth generada para email [masked]");
            return otpAuthUrl;

        } catch (Exception e) {
            log.error("Error generando URL otpauth: {}", e.getClass().getSimpleName());
            throw new RuntimeException("Error generando URL otpauth", e);
        }
    }

    /**
     * Para compatibilidad con código existente.
     */
    public String generateQRCodeImageUri(String secret, String email) {
        return generateOtpAuthUrl(secret, email);
    }

    /**
     * Genera el QR code en base64 usando el secret exacto de la BD.
     */
    public String generateQRCodeBase64(String secret, String email) throws WriterException, IOException {
        log.debug("Generando QR code para email [masked]");

        String qrCodeUri = generateOtpAuthUrl(secret, email);

        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(qrCodeUri, BarcodeFormat.QR_CODE, 300, 300);

        ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);

        String base64QR = Base64.getEncoder().encodeToString(pngOutputStream.toByteArray());

        log.debug("QR code generado exitosamente");
        return base64QR;
    }

    /** Highest matching counter within the existing ±2 slots; no code is persisted or returned. */
    public java.util.OptionalLong findMatchingSlot(String secret, String code) {
        if (secret == null || code == null || !code.matches("[0-9]{6}")) return java.util.OptionalLong.empty();
        try {
            int supplied = Integer.parseInt(code);
            long currentSlot = clock.millis() / 30000L;
            for (int offset = 2; offset >= -2; offset--) {
                long slot = currentSlot + offset;
                if (slot >= 0 && supplied == gAuth.getTotpPassword(secret, slot * 30000L)) {
                    return java.util.OptionalLong.of(slot);
                }
            }
        } catch (Exception ex) {
            log.warn("No se pudo validar TOTP: {}", ex.getClass().getSimpleName());
        }
        return java.util.OptionalLong.empty();
    }

    public boolean verifyCode(String secret, int code) {
        if (code < 0 || code > 999999) return false;
        return verifyCode(secret, String.format(java.util.Locale.ROOT, "%06d", code));
    }

    public boolean verifyCode(String secret, String code) {
        return findMatchingSlot(secret, code).isPresent();
    }
}
