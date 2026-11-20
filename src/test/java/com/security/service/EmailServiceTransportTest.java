package com.security.service;

import com.security.entity.User;
import com.security.exception.AuthFlowException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class EmailServiceTransportTest {
    EmailService service;
    JavaMailSender smtp;
    MockRestServiceServer http;
    User user;

    @BeforeEach void setup() {
        service = new EmailService();
        smtp = mock(JavaMailSender.class);
        var rest = new RestTemplate();
        http = MockRestServiceServer.bindTo(rest).build();
        ReflectionTestUtils.setField(service, "mailSender", smtp);
        ReflectionTestUtils.setField(service, "restTemplate", rest);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:4200/");
        ReflectionTestUtils.setField(service, "senderName", "Casa de Música Castillo");
        ReflectionTestUtils.setField(service, "senderAddress", "sender@example.invalid");
        user = new User();
        user.setEmail("recipient@example.invalid");
        user.setFirstName("Synthetic");
    }

    @Test void verificationUsesBrevoHttpsAndDoesNotFallBackToSmtp() {
        ReflectionTestUtils.setField(service, "brevoApiKey", "synthetic-brevo-key");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("api-key", "synthetic-brevo-key"))
                .andExpect(jsonPath("$.sender.email").value("sender@example.invalid"))
                .andExpect(jsonPath("$.to[0].email").value(user.getEmail()))
                .andExpect(jsonPath("$.subject").value("Verificación de cuenta - Casa de Música Castillo"))
                .andExpect(jsonPath("$.htmlContent").value(org.hamcrest.Matchers.containsString(
                        "http://localhost:4200/verify-account?token=synthetic-token")))
                .andRespond(withSuccess("{\"messageId\":\"synthetic-id\"}", MediaType.APPLICATION_JSON));
        service.sendVerificationEmail(user, "synthetic-token");
        http.verify();
        verifyNoInteractions(smtp);
    }

    @Test void recoveryUsesResendHttps() {
        ReflectionTestUtils.setField(service, "resendApiKey", "synthetic-resend-key");
        http.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(header("Authorization", "Bearer synthetic-resend-key"))
                .andExpect(jsonPath("$.to[0]").value(user.getEmail()))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString(
                        "http://localhost:4200/reset-password?token=synthetic_A-b")))
                .andRespond(withSuccess("{\"id\":\"synthetic-id\"}", MediaType.APPLICATION_JSON));
        service.sendPasswordResetEmail(user, "synthetic_A-b");
        http.verify();
        verifyNoInteractions(smtp);
    }

    @Test void rejectedBrevoFallsBackToResendForTwoFactor() {
        ReflectionTestUtils.setField(service, "brevoApiKey", "synthetic-brevo-key");
        ReflectionTestUtils.setField(service, "resendApiKey", "synthetic-resend-key");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        http.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(jsonPath("$.subject").value("Código de verificación 2FA - Casa de Música Castillo"))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("123456")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        service.send2FACodeEmail(user, "123456");
        http.verify();
        verifyNoInteractions(smtp);
    }

    @Test void completeDeliveryFailurePropagatesInsteadOfReportingSuccess() {
        ReflectionTestUtils.setField(service, "brevoApiKey", "synthetic-brevo-key");
        ReflectionTestUtils.setField(service, "resendApiKey", "synthetic-resend-key");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        http.expect(requestTo("https://api.resend.com/emails")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(smtp.createMimeMessage()).thenReturn(message);
        doThrow(new org.springframework.mail.MailSendException("synthetic failure")).when(smtp).send(any(MimeMessage.class));
        assertThrows(AuthFlowException.class, () -> service.sendVerificationEmail(user, "synthetic-token"));
        http.verify();
        verify(smtp).send(message);
    }

    @Test void activationTimeoutIsUnknownWithoutRetryOrFallback() {
        ReflectionTestUtils.setField(service, "brevoApiKey", "synthetic-key");
        ReflectionTestUtils.setField(service, "resendApiKey", "synthetic-alternative");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andRespond(withException(new java.net.SocketTimeoutException("synthetic")));
        assertEquals(EmailService.ActivationDelivery.UNKNOWN, service.deliverActivation(user,"synthetic-token","012345"));
        http.verify(); verifyNoInteractions(smtp);
    }

    @Test void activationConfirmedRejectionDoesNotFallbackAndCanBeRecoveredExplicitly() {
        ReflectionTestUtils.setField(service, "brevoApiKey", "synthetic-key");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertEquals(EmailService.ActivationDelivery.FAILED, service.deliverActivation(user,"synthetic-token","012345"));
        http.verify(); verifyNoInteractions(smtp);
    }

    @Test void activationAcceptedIsOneRequestAndNoFallback() {
        ReflectionTestUtils.setField(service, "brevoApiKey", "synthetic-key");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertEquals(EmailService.ActivationDelivery.ACCEPTED, service.deliverActivation(user,"synthetic-token","012345"));
        http.verify(); verifyNoInteractions(smtp);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={401,429,500})
    void recoveryAndNoticeNeverFallBackOrRetryOnProviderResponses(int code) {
        ReflectionTestUtils.setField(service,"brevoApiKey","synthetic-brevo-key");
        ReflectionTestUtils.setField(service,"resendApiKey","synthetic-alternative");
        ReflectionTestUtils.setField(service,"baseUrl","https://shop.example.test");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andExpect(header("api-key","synthetic-brevo-key"))
            .andExpect(jsonPath("$.htmlContent").value(org.hamcrest.Matchers.containsString("https://shop.example.test/reset-password?token=synthetic-token")))
            .andRespond(withStatus(HttpStatus.valueOf(code)));
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andExpect(jsonPath("$.subject").value("Se actualizó la contraseña de tu cuenta — Casa de Música Castillo"))
            .andExpect(jsonPath("$.htmlContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-token"))))
            .andRespond(withStatus(HttpStatus.valueOf(code)));
        assertEquals(code>=500?EmailService.ActivationDelivery.UNKNOWN:EmailService.ActivationDelivery.FAILED,
            service.deliverPasswordReset(user,"synthetic-token"));
        assertEquals(code>=500?EmailService.ActivationDelivery.UNKNOWN:EmailService.ActivationDelivery.FAILED,
            service.deliverPasswordChanged(user));
        http.verify();verifyNoInteractions(smtp);
    }
    @Test void recoveryAndNotificationAcceptOneRequestOrRecordUnknownAfterTimeout() {
        ReflectionTestUtils.setField(service,"brevoApiKey","synthetic-brevo-key");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andRespond(withSuccess("{\"messageId\":\"synthetic-id\"}",MediaType.APPLICATION_JSON));
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andRespond(withException(new java.net.SocketTimeoutException("synthetic")));
        assertEquals(EmailService.ActivationDelivery.ACCEPTED,service.deliverPasswordReset(user,"synthetic-token"));
        assertEquals(EmailService.ActivationDelivery.UNKNOWN,service.deliverPasswordChanged(user));
        http.verify();verifyNoInteractions(smtp);
    }
    @Test void brandedNoticeContractIsIdenticalForHtmlAndTextInBrevo() {
        ReflectionTestUtils.setField(service,"brevoApiKey","synthetic-brevo-key");
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("api-key","synthetic-brevo-key"))
            .andExpect(header("Content-Type",org.hamcrest.Matchers.startsWith("application/json")))
            .andExpect(jsonPath("$.sender.email").value("sender@example.invalid"))
            .andExpect(jsonPath("$.subject").value("Se actualizó la contraseña de tu cuenta — Casa de Música Castillo"))
            .andExpect(jsonPath("$.htmlContent").value(org.hamcrest.Matchers.containsString("max-width:600px")))
            .andExpect(jsonPath("$.textContent").value(org.hamcrest.Matchers.containsString("Tu contraseña fue actualizada")))
            .andExpect(jsonPath("$.htmlContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("token="))))
            .andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertEquals(EmailService.ActivationDelivery.ACCEPTED,service.deliverPasswordChanged(user));
        http.verify();verifyNoInteractions(smtp);
    }
    @Test void smtpNoticePreservesUtf8AndBothMimeAlternatives() throws Exception {
        MimeMessage message=new MimeMessage(Session.getInstance(new Properties()));
        when(smtp.createMimeMessage()).thenReturn(message);
        assertEquals(EmailService.ActivationDelivery.ACCEPTED,service.deliverPasswordChanged(user));
        verify(smtp,times(1)).send(message);message.saveChanges();
        assertEquals("Se actualizó la contraseña de tu cuenta — Casa de Música Castillo",message.getSubject());
        assertTrue(message.getContentType().startsWith("multipart/"));
        var bytes=new java.io.ByteArrayOutputStream();message.writeTo(bytes);
        var parsed=new MimeMessage(Session.getInstance(new Properties()),new java.io.ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(message.getSubject(),parsed.getSubject());
        assertTrue(hasMimePart(parsed,"text/plain"));assertTrue(hasMimePart(parsed,"text/html"));
    }
    private boolean hasMimePart(jakarta.mail.Part part,String type) throws Exception {
        if(part.isMimeType(type))return true;
        if(part.getContent() instanceof jakarta.mail.Multipart multipart)
            for(int i=0;i<multipart.getCount();i++)if(hasMimePart(multipart.getBodyPart(i),type))return true;
        return false;
    }

}
