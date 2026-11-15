package com.security.controller;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class EmailMimeCaptureTest {
    private MimeMessage message() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private AuthEmailFlowsIntegrationTest.CapturedEmail capture(MimeMessage message) throws Exception {
        message.setSubject("Verificación y música", "UTF-8");
        message.saveChanges();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        var server = new AuthEmailFlowsIntegrationTest.LocalSmtpCaptureServer();
        server.parseAndStoreMime("sender@example.invalid", "recipient@example.invalid", bytes.toByteArray());
        assertEquals(1, server.getCapturedEmails().size());
        var captured = server.getCapturedEmails().get(0);
        assertEquals("Verificación y música", captured.subject);
        assertFalse(captured.body.contains("MimeMultipart@"));
        return captured;
    }

    @Test void decodesPlainTextWithDeclaredCharset() throws Exception {
        var mail = message();
        mail.setText("Contraseña y música: áéíóú ñ", "ISO-8859-1");
        assertEquals("Contraseña y música: áéíóú ñ", capture(mail).body);
    }

    @Test void decodesHtmlAndBase64UrlTokenWithoutTruncation() throws Exception {
        var mail = message();
        String html = "<a href=\"http://localhost:4200/reset-password?token=synthetic_A-b\">Música</a>";
        mail.setText(html, "UTF-8", "html");
        mail.setHeader("Content-Transfer-Encoding", "base64");
        assertEquals(html, capture(mail).body);
    }

    @Test void decodesNestedMultipartAndSkipsBinaryAttachments() throws Exception {
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Texto de verificación", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setText("<p>Código: 123456</p>", "UTF-8", "html");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(html);
        MimeBodyPart nested = new MimeBodyPart();
        nested.setContent(alternative);
        MimeBodyPart binary = new MimeBodyPart();
        binary.setContent(new byte[]{1, 2, 3}, "application/octet-stream");
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(nested);
        mixed.addBodyPart(binary);
        var mail = message();
        mail.setContent(mixed);
        String body = capture(mail).body;
        assertTrue(body.contains("Texto de verificación"));
        assertTrue(body.contains("<p>Código: 123456</p>"));
    }

    @Test void decodesEncapsulatedMessage() throws Exception {
        var nested = message();
        nested.setText("Enlace anidado: http://localhost:4200/verify-account?token=synthetic_A-b", "UTF-8");
        var mail = message();
        mail.setContent(nested, "message/rfc822");
        assertTrue(capture(mail).body.contains("verify-account?token=synthetic_A-b"));
    }
}