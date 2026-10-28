package com.security.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.entity.PasswordResetToken;
import com.security.entity.Role;
import com.security.entity.User;
import com.security.entity.VerificationToken;
import com.security.repository.ActiveSessionRepository;
import com.security.repository.PasswordRecoveryAttemptRepository;
import com.security.repository.PasswordResetTokenRepository;
import com.security.repository.RoleRepository;
import com.security.repository.UserRepository;
import com.security.repository.VerificationTokenRepository;
import com.security.service.TwoFactorService;
import com.security.support.LocalPostgresIntegrationTest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración completa de los flujos de:
 * - Registro de usuarios (escenarios A, B, C)
 * - Envío y contenido de correos de verificación (escenarios D, E)
 * - Confirmación de cuenta y manejo de tokens válidos/inválidos/expirados (escenarios F, G, H)
 * - Reenvío de verificación con rotación de token y anti-enumeración (escenario I)
 * - Solicitud, recepción y restablecimiento seguro de contraseña con revocación de sesiones (escenarios J, K, L, M, N)
 * - Manejo de fallo real del proveedor de correo sin falsos positivos (escenario O)
 * - Envío y validación de códigos 2FA por correo electrónico con límite de intentos (escenario P)
 */
@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class AuthEmailFlowsIntegrationTest extends LocalPostgresIntegrationTest {

    private static final LocalSmtpCaptureServer SMTP_SERVER = new LocalSmtpCaptureServer();

    @DynamicPropertySource
    static void configureLocalSmtp(DynamicPropertyRegistry registry) {
        SMTP_SERVER.ensureStarted();
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", SMTP_SERVER::getPort);
        registry.add("spring.mail.username", () -> "test-smtp@casamusicacastillo.local");
        registry.add("spring.mail.password", () -> "synthetic-smtp-pass");
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add("app.email.brevo.api-key", () -> "");
        registry.add("app.email.resend.api-key", () -> "");
        registry.add("app.email.sender-name", () -> "Casa de Música Castillo");
        registry.add("app.email.sender-address", () -> "no-reply@casamusicacastillo.local");
        registry.add("app.frontend.url", () -> "http://localhost:4200/");
        registry.add("app.base-url", () -> "http://localhost:4200/");
    }

    @AfterAll
    static void stopSmtpServer() {
        SMTP_SERVER.stop();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordRecoveryAttemptRepository passwordRecoveryAttemptRepository;

    @Autowired
    private ActiveSessionRepository activeSessionRepository;

    @Autowired
    private TwoFactorService twoFactorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private com.security.config.MercadoPagoConfiguration mercadoPagoConfiguration;

    @Autowired
    private org.springframework.web.client.RestTemplate restTemplate;
    private org.springframework.test.web.client.MockRestServiceServer httpCapture;

    private final List<String> testEmailsToCleanup = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // No HTTP provider can leave this process, even with ambient production credentials.
        httpCapture = org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).build();
        SMTP_SERVER.clear();
        SMTP_SERVER.setFailMode(false);
        // This synthetic nonexistent address also creates rate-limit rows; isolate repeated runs.
        testEmailsToCleanup.add("inexistente.recuperacion@example.invalid");
        jdbcTemplate.update("DELETE FROM password_recovery_attempts WHERE email = ?", "inexistente.recuperacion@example.invalid");
        if (roleRepository.findByName("ROLE_USER").isEmpty()) {
            Role role = new Role();
            role.setName("ROLE_USER");
            role.setDescription("Rol de cliente");
            roleRepository.save(role);
        }
    }

    @AfterEach
    void tearDown() {
        SMTP_SERVER.setFailMode(false);
        for (String email : testEmailsToCleanup) {
            userRepository.findByEmail(email).ifPresent(u -> {
                jdbcTemplate.update("DELETE FROM auth.login_challenges WHERE user_id = ?", u.getId());
                jdbcTemplate.update("DELETE FROM auth.login_factor_limits WHERE user_id = ?", u.getId());
                activeSessionRepository.deleteAll(activeSessionRepository.findByUserId(u.getId()));
                verificationTokenRepository.findByUserId(u.getId()).forEach(verificationTokenRepository::delete);
                passwordResetTokenRepository.deleteAllByUser(u);
                userRepository.deleteById(u.getId());
            });
            jdbcTemplate.update("DELETE FROM password_recovery_attempts WHERE email = ?", email);
            jdbcTemplate.update("DELETE FROM login_attempts WHERE email = ?", email);
        }
        testEmailsToCleanup.clear();
        httpCapture.verify();
    }

    private static String userOf(String email) {
        String local = email.substring(0, email.indexOf('@')).replace('.', '_');
        return local.length() > 30 ? local.substring(0, 30) : local;
    }

    private String registerEmail(String prefix) {
        String email = prefix + "@gmail.com";
        testEmailsToCleanup.add(email);
        return email;
    }

    /**
     * Escenarios A, D, E, F, G, H, I:
     * Registro válido -> Captura de correo de verificación -> Validación de enlace ->
     * Tokens inválidos/expirados -> Reenvío de verificación -> Activación y Login.
     */
    @Test
    void registrationVerificationAndResendFullCycle() throws Exception {
        String email = registerEmail("cliente.verificacion");
        String password = "Fuerte2026!Castillo<Ok>"; // Incluye palabra con "ert" y símbolos '<', '>'

        // Escenario A: Registro válido
        Map<String, Object> registerPayload = Map.of(
                "username", "cliente_verif",
                "email", email,
                "password", password,
                "firstName", "Iván",
                "lastName", "Ramírez",
                "phone", "+52 55 1234 5678"
        );

        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(registerPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        User savedUser = userRepository.findByEmail(email).orElseThrow();
        assertFalse(savedUser.getEnabled(), "El usuario recién registrado debe tener enabled=false hasta verificar su correo");

        // Intento de login antes de verificar cuenta debe ser rechazado con 401
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cuenta no verificada")));

        // Escenarios D y E: Generación y contenido del correo de verificación
        assertEquals(1, SMTP_SERVER.getCapturedEmails().size(), "Debe haberse enviado exactamente 1 correo de verificación");
        CapturedEmail verifMail = SMTP_SERVER.getCapturedEmails().get(0);
        assertEquals(email, verifMail.to);
        assertEquals("Verificación de cuenta - Casa de Música Castillo", verifMail.subject);
        assertTrue(verifMail.body.contains("http://localhost:4200/verify-account?token="),
                "El enlace debe apuntar a /verify-account?token= sin doble barra");
        assertFalse(verifMail.body.contains("localhost:4200//verify-account"),
                "No debe existir doble barra en la URL de verificación");

        String firstToken = extractQueryToken(verifMail.body, "/verify-account\\?token=([A-Za-z0-9_\\-]+)");
        assertNotNull(firstToken, "El correo debe incluir un token de verificación extraíble");

        // Escenario G: Token inválido
        mvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("token", "token-inexistente-0000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("El enlace de verificación es inválido o ha expirado."));

        // Escenario H: Token expirado
        VerificationToken vtEntity = verificationTokenRepository.findByToken(firstToken).orElseThrow();
        vtEntity.setExpiryDate(LocalDateTime.now().minusHours(2));
        verificationTokenRepository.save(vtEntity);

        mvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("token", firstToken))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("El enlace de verificación es inválido o ha expirado."));

        // Escenario I: Reenvío de verificación
        SMTP_SERVER.clear();
        mvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, SMTP_SERVER.getCapturedEmails().size(), "El reenvío debe emitir un nuevo correo");
        CapturedEmail resentMail = SMTP_SERVER.getCapturedEmails().get(0);
        String secondToken = extractQueryToken(resentMail.body, "/verify-account\\?token=([A-Za-z0-9_\\-]+)");
        assertNotNull(secondToken);
        assertFalse(firstToken.equals(secondToken), "El reenvío debe rotar el token de verificación");
        assertTrue(verificationTokenRepository.findByToken(firstToken).isEmpty(),
                "El token anterior debe haber sido eliminado al rotar");

        // Escenario F: Confirmación con token válido
        mvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("token", secondToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        User verifiedUser = userRepository.findByEmail(email).orElseThrow();
        assertTrue(verifiedUser.getEnabled(), "El usuario debe quedar activo (enabled=true) tras verificar");
        VerificationToken usedVt = verificationTokenRepository.findByToken(secondToken).orElseThrow();
        assertTrue(Boolean.TRUE.equals(usedVt.getUsed()), "El token de verificación debe marcarse como usado (used=true)");

        // Reutilización del mismo token de verificación debe fallar con 400
        mvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("token", secondToken))))
                .andExpect(status().isBadRequest());

        // Reenvío de verificación para cuenta ya verificada o correo inexistente no debe revelar estado ni enviar correo
        SMTP_SERVER.clear();
        mvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", "noexiste@example.invalid"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertTrue(SMTP_SERVER.getCapturedEmails().isEmpty(), "No debe enviarse correo para cuentas ya activas o inexistentes");

        // Login exitoso con la contraseña exacta (incluyendo '<' y '>')
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jwtResponse.accessToken").isNotEmpty());
    }

    /**
     * Escenario B y F (GET /api/auth/verify redirect):
     * Registro duplicado (ghost user no verificado vs usuario ya activo) y redirección GET /api/auth/verify.
     */
    @Test
    void duplicateRegistrationAndGetVerifyRedirect() throws Exception {
        String email = registerEmail("duplicado.registro");
        String password = "ClaveSegura2026!#";

        Map<String, Object> firstAttempt = Map.of(
                "username", "dup_user_1",
                "email", email,
                "password", password,
                "firstName", "Carlos",
                "lastName", "Castillo"
        );

        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(firstAttempt)))
                .andExpect(status().isOk());

        assertEquals(1, SMTP_SERVER.getCapturedEmails().size());
        String firstToken = extractQueryToken(SMTP_SERVER.getCapturedEmails().get(0).body,
                "/verify-account\\?token=([A-Za-z0-9_\\-]+)");

        // B1: Segundo registro sobre cuenta aún NO verificada (ghost account upsert)
        SMTP_SERVER.clear();
        String updatedPassword = "NuevaClave2026!$";
        Map<String, Object> secondAttempt = Map.of(
                "username", "dup_user_2",
                "email", email,
                "password", updatedPassword,
                "firstName", "Carlos Alberto",
                "lastName", "Castillo"
        );

        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(secondAttempt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, SMTP_SERVER.getCapturedEmails().size(), "El re-registro de cuenta no verificada debe enviar nuevo correo");
        String secondToken = extractQueryToken(SMTP_SERVER.getCapturedEmails().get(0).body,
                "/verify-account\\?token=([A-Za-z0-9_\\-]+)");
        assertFalse(java.util.Objects.equals(firstToken, secondToken), "El nuevo registro debe rotar el token");

        // Verificar mediante GET /api/auth/verify?token=... (debe redirigir a frontendBaseUrl, no hardcoded localhost si cambia)
        mvc.perform(get("/api/auth/verify").param("token", secondToken))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:4200/login?verified=true"));

        // B2: Registro duplicado sobre cuenta YA verificada y activa -> respuesta uniforme 200 OK sin enviar correo ni alterar contraseña
        SMTP_SERVER.clear();
        Map<String, Object> thirdAttempt = Map.of(
                "username", "atacante_user",
                "email", email,
                "password", "IntentoSobrescribir2026!",
                "firstName", "Atacante",
                "lastName", "Externo"
        );

        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(thirdAttempt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertTrue(SMTP_SERVER.getCapturedEmails().isEmpty(), "No debe enviar correo de verificación a cuenta ya activa");

        // La contraseña legítima del usuario activo sigue funcionando
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", updatedPassword))))
                .andExpect(status().isOk());
    }

    /**
     * Escenario C:
     * Validación de datos inválidos en el registro (contraseña débil, >72 bytes UTF-8, email inválido, nombre inválido).
     */
    @Test
    void invalidRegistrationInputsReturn400WithoutReflectingSensitiveValues() throws Exception {
        String email = registerEmail("invalido.test");

        // Contraseña débil
        String weakSecret = "weakpass";
        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "username", userOf(email), "email", email,
                                "password", weakSecret,
                                "firstName", "Ana",
                                "lastName", "López"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains(weakSecret),
                        "No debe reflejar la contraseña rechazada en la respuesta"));

        // Contraseña que excede 72 bytes UTF-8
        String over72BytesPassword = "Valid1!A" + "a".repeat(66); // 74 bytes
        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "username", userOf(email), "email", email,
                                "password", over72BytesPassword,
                                "firstName", "Ana",
                                "lastName", "López"
                        ))))
                .andExpect(status().isBadRequest());

        // Email inválido
        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "username", userOf(email), "email", "correo-invalido-sin-dominio",
                                "password", "ClaveValida2026!",
                                "firstName", "Ana",
                                "lastName", "López"
                        ))))
                .andExpect(status().isBadRequest());

        // Nombre inválido con dígitos
        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "username", userOf(email), "email", email,
                                "password", "ClaveValida2026!",
                                "firstName", "Ana1234",
                                "lastName", "López"
                        ))))
                .andExpect(status().isBadRequest());

        assertTrue(SMTP_SERVER.getCapturedEmails().isEmpty());
    }

    /**
     * Escenarios J, K, L, M, N:
     * Solicitud de recuperación -> Recepción de correo -> Validación de token ->
     * Restablecimiento de contraseña -> Revocación de sesiones activas -> Prohibición de reutilización ->
     * Anti-enumeración en correo inexistente.
     */
    @Test
    void passwordRecoveryResetSessionRevocationAndTokenSingleUse() throws Exception {
        String email = registerEmail("recuperacion.cuenta");
        String initialPassword = "ClaveInicial2026!Castillo";
        String resetPassword = "NuevaClave<2026>&Segura!";

        // 1. Registrar y verificar usuario
        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "username", userOf(email), "email", email,
                                "password", initialPassword,
                                "firstName", "Elena",
                                "lastName", "Gómez"
                        ))))
                .andExpect(status().isOk());

        String verifToken = extractQueryToken(SMTP_SERVER.getCapturedEmails().get(0).body,
                "/verify-account\\?token=([A-Za-z0-9_\\-]+)");
        mvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("token", verifToken))))
                .andExpect(status().isOk());

        // 2. Iniciar sesión para obtener un JWT activo previo al reset
        var loginRes = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                .andExpect(status().isOk())
                .andReturn();
        String activeJwtBeforeReset = json.readTree(loginRes.getResponse().getContentAsString())
                .get("data").get("jwtResponse").get("accessToken").asText();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + activeJwtBeforeReset))
                .andExpect(status().isOk());

        // Escenario N: Solicitud de recuperación para correo inexistente -> 200 OK sin enviar email
        SMTP_SERVER.clear();
        mvc.perform(post("/api/auth/forgot-password").param("email", "inexistente.recuperacion@example.invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertTrue(SMTP_SERVER.getCapturedEmails().isEmpty(), "No debe enviarse correo para un email inexistente");

        // Escenarios J y K: Solicitud de recuperación para usuario real y captura de correo
        mvc.perform(post("/api/auth/forgot-password").param("email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, SMTP_SERVER.getCapturedEmails().size(), "Debe haberse enviado el correo de recuperación");
        CapturedEmail resetMail = SMTP_SERVER.getCapturedEmails().get(0);
        assertEquals(email, resetMail.to);
        assertEquals("Recuperación de contraseña - Casa de Música Castillo", resetMail.subject);
        assertTrue(resetMail.body.contains("http://localhost:4200/reset-password?token="));

        String resetToken = extractQueryToken(resetMail.body, "/reset-password\\?token=([A-Za-z0-9_\\-]+)");
        assertNotNull(resetToken);

        // Validar token antes de consumirlo
        mvc.perform(get("/api/auth/validate-reset-token").param("token", resetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Escenario L: Restablecimiento válido
        mvc.perform(post("/api/auth/reset-password")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "token", resetToken,
                                "newPassword", resetPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        PasswordResetToken consumedToken = passwordResetTokenRepository.findByToken(resetToken).orElseThrow();
        assertTrue(Boolean.TRUE.equals(consumedToken.getUsed()), "El token de recuperación debe quedar marcado como usado");

        // Verificar que la sesión JWT anterior fue revocada tras el cambio de contraseña
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + activeJwtBeforeReset))
                .andExpect(status().isUnauthorized());

        // Verificar que la contraseña anterior ya no funciona
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                .andExpect(status().isUnauthorized());

        // Verificar que la nueva contraseña (con '<', '>', '&') inicia sesión correctamente
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", resetPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jwtResponse.accessToken").isNotEmpty());

        // Escenario M: Reutilización del token ya usado debe fallar con 400
        mvc.perform(get("/api/auth/validate-reset-token").param("token", resetToken))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/auth/reset-password")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "token", resetToken,
                                "newPassword", "OtraClaveValida2026!"
                        ))))
                .andExpect(status().isBadRequest());
    }

    /**
     * Escenario O:
     * Fallo real del proveedor de correo devuelve 503 Service Unavailable sin falso positivo,
     * manteniendo el registro pendiente en BD para poder recuperar mediante reenvío cuando el servicio vuelve.
     */
    @Test
    void mailProviderFailureReturns503WithoutFalsePositiveAndAllowsSubsequentRecovery() throws Exception {
        String email = registerEmail("fallo.smtp");
        String password = "ClaveSegura2026!Smtp";

        SMTP_SERVER.setFailMode(true);

        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "username", userOf(email), "email", email,
                                "password", password,
                                "firstName", "Roberto",
                                "lastName", "Sánchez"
                        ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));

        // El usuario queda registrado como no verificado (enabled=false) fuera de la llamada de red fallida
        User pendingUser = userRepository.findByEmail(email).orElseThrow();
        assertFalse(pendingUser.getEnabled());

        // Cuando el servicio SMTP se restablece, el usuario puede reenviar el correo de verificación y activar su cuenta
        SMTP_SERVER.setFailMode(false);
        SMTP_SERVER.clear();

        mvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, SMTP_SERVER.getCapturedEmails().size());
        String verifToken = extractQueryToken(SMTP_SERVER.getCapturedEmails().get(0).body,
                "/verify-account\\?token=([A-Za-z0-9_\\-]+)");
        assertNotNull(verifToken);

        mvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("token", verifToken))))
                .andExpect(status().isOk());

        // Ahora simulamos caída del proveedor de correo durante forgot-password -> debe devolver 503
        SMTP_SERVER.setFailMode(true);
        mvc.perform(post("/api/auth/forgot-password").param("email", email))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * Escenario P:
     * Envío y validación de códigos 2FA por correo electrónico (flujo oficial de login con challenge + límite de intentos).
     */
    @Test
    void twoFactorEmailCodeSendCaptureAndVerify() throws Exception {
        String email = registerEmail("usuario.2fa.email");
        String password = "Clave2FA2026!Segura";

        // 1. Registrar y activar usuario
        mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "username", userOf(email), "email", email,
                                "password", password,
                                "firstName", "Lucía",
                                "lastName", "Méndez"
                        ))))
                .andExpect(status().isOk());

        String verifToken = extractQueryToken(SMTP_SERVER.getCapturedEmails().get(0).body,
                "/verify-account\\?token=([A-Za-z0-9_\\-]+)");
        mvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("token", verifToken))))
                .andExpect(status().isOk());

        // 2. Habilitar 2FA por correo para el usuario
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailEnabled(true);
        user.setTwoFactorEnabled(true);
        userRepository.save(user);

        // 3. Login inicia challenge 2FA
        SMTP_SERVER.clear();
        var loginResult = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode dataNode = json.readTree(loginResult.getResponse().getContentAsString()).get("data");
        assertTrue(dataNode.get("twoFactorRequired").asBoolean());
        String challenge = dataNode.get("preAuthChallenge").asText();

        // 4. Solicitar envío de código 2FA por EMAIL
        mvc.perform(post("/api/2fa/send-login-code")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", challenge,
                                "method", "EMAIL"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, SMTP_SERVER.getCapturedEmails().size(), "Debe haberse capturado el correo con el código 2FA");
        CapturedEmail twoFactorMail = SMTP_SERVER.getCapturedEmails().get(0);
        assertEquals(email, twoFactorMail.to);
        assertEquals("Código de verificación 2FA - Casa de Música Castillo", twoFactorMail.subject);

        String sixDigitCode = extractSixDigitCode(twoFactorMail.body);
        assertNotNull(sixDigitCode, "El correo 2FA debe contener un código de 6 dígitos");

        // 5. Verificar código 2FA válido emite el JWT
        mvc.perform(post("/api/2fa/verify")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", challenge,
                                "method", "EMAIL",
                                "code", sixDigitCode
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        // Expire a fresh official login challenge in PostgreSQL, without sleeping.
        SMTP_SERVER.clear();
        var expiredLogin = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        String expiredChallenge = json.readTree(expiredLogin.getResponse().getContentAsString())
                .path("data").path("preAuthChallenge").asText();
        assertFalse(expiredChallenge.isBlank());
        mvc.perform(post("/api/2fa/send-login-code").contentType("application/json")
                .content(json.writeValueAsString(Map.of("preAuthChallenge", expiredChallenge, "method", "EMAIL"))))
                .andExpect(status().isOk());
        assertEquals(1, SMTP_SERVER.getCapturedEmails().size());
        String expiredCode = extractSixDigitCode(SMTP_SERVER.getCapturedEmails().get(0).body);
        assertNotNull(expiredCode);
        assertEquals(1, jdbcTemplate.update("UPDATE auth.login_challenges SET created_at=now()-interval '10 minutes', "
                + "expires_at=now()-interval '1 minute' WHERE user_id=? AND consumed_at IS NULL", user.getId()));
        mvc.perform(post("/api/2fa/verify").contentType("application/json")
                .content(json.writeValueAsString(Map.of("preAuthChallenge", expiredChallenge,
                        "method", "EMAIL", "code", expiredCode))))
                .andExpect(status().isUnauthorized());
        // 6. Probar límite de intentos en TwoFactorService.verifyEmailCode (5 intentos fallidos invalidan el código)
        SMTP_SERVER.clear();
        twoFactorService.sendEmailCode(user.getId());
        assertEquals(1, SMTP_SERVER.getCapturedEmails().size());
        String profile2faCode = extractSixDigitCode(SMTP_SERVER.getCapturedEmails().get(0).body);
        assertNotNull(profile2faCode);

        for (int i = 0; i < 5; i++) {
            assertFalse(twoFactorService.verifyEmailCode(user.getId(), profile2faCode.equals("000000") ? "000001" : "000000"));
        }
        assertFalse(twoFactorService.verifyEmailCode(user.getId(), profile2faCode),
                "Tras 5 intentos fallidos, el código 2FA de email debe quedar invalidado");
    }

    @Test
    void expiredPasswordResetIsRejectedWithoutChangingPassword() throws Exception {
        String email = registerEmail("reset.expirado");
        String password = "ClaveSegura2026!Exp";
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", userOf(email), "email", email,
                        "password", password, "firstName", "Prueba", "lastName", "Expirada"))))
                .andExpect(status().isOk());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEnabled(true);
        userRepository.save(user);
        SMTP_SERVER.clear();
        mvc.perform(post("/api/auth/forgot-password").param("email", email)).andExpect(status().isOk());
        assertEquals(1, SMTP_SERVER.getCapturedEmails().size());
        String token = extractQueryToken(SMTP_SERVER.getCapturedEmails().get(0).body,
                "/reset-password\\?token=([A-Za-z0-9_\\-]+)");
        assertNotNull(token);
        PasswordResetToken entity = passwordResetTokenRepository.findByToken(token).orElseThrow();
        entity.setExpiryDate(LocalDateTime.now().minusMinutes(1));
        passwordResetTokenRepository.save(entity);
        mvc.perform(get("/api/auth/validate-reset-token").param("token", token)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                .content(json.writeValueAsString(Map.of("token", token, "newPassword", "NuevaClave2026!Exp"))))
                .andExpect(status().isBadRequest());
        assertEquals(user.getPassword(), userRepository.findByEmail(email).orElseThrow().getPassword());
        assertFalse(passwordResetTokenRepository.findByToken(token).orElseThrow().getUsed());
    }
    private static String extractQueryToken(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractSixDigitCode(String html) {
        Matcher matcher = Pattern.compile("class=\"code\">\\s*(\\d{6})\\s*</div>").matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher fallback = Pattern.compile("\\b(\\d{6})\\b").matcher(html);
        return fallback.find() ? fallback.group(1) : null;
    }

    static class CapturedEmail {
        final String from;
        final String to;
        final String subject;
        final String body;

        CapturedEmail(String from, String to, String subject, String body) {
            this.from = from;
            this.to = to;
            this.subject = subject;
            this.body = body;
        }
    }

    /**
     * Servidor SMTP local ligero en 127.0.0.1 para capturar mensajes MIME reales enviados por JavaMailSender.
     */
    static class LocalSmtpCaptureServer {
        private ServerSocket serverSocket;
        private ExecutorService executor;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean failMode = new AtomicBoolean(false);
        private final List<CapturedEmail> capturedEmails = new CopyOnWriteArrayList<>();

        synchronized void ensureStarted() {
            if (running.get()) {
                return;
            }
            try {
                serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                running.set(true);
                executor = Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "local-smtp-capture");
                    t.setDaemon(true);
                    return t;
                });
                executor.submit(this::acceptLoop);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo iniciar LocalSmtpCaptureServer", e);
            }
        }

        int getPort() {
            ensureStarted();
            return serverSocket.getLocalPort();
        }

        void setFailMode(boolean fail) {
            failMode.set(fail);
        }

        void clear() {
            capturedEmails.clear();
        }

        List<CapturedEmail> getCapturedEmails() {
            return capturedEmails;
        }

        synchronized void stop() {
            running.set(false);
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
            if (executor != null) {
                executor.shutdownNow();
            }
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    executor.submit(() -> handleClient(socket));
                } catch (IOException e) {
                    if (running.get()) {
                        // continue
                    }
                }
            }
        }

        private void handleClient(Socket socket) {
            try (socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                if (failMode.get()) {
                    writer.print("421 4.3.2 Service not available\r\n");
                    writer.flush();
                    return;
                }

                writer.print("220 localhost ESMTP LocalSmtpCaptureServer\r\n");
                writer.flush();

                String line;
                String from = "";
                String to = "";
                while ((line = reader.readLine()) != null) {
                    String upper = line.toUpperCase();
                    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                        writer.print("250-localhost\r\n250 OK\r\n");
                        writer.flush();
                    } else if (upper.startsWith("MAIL FROM:")) {
                        if (failMode.get()) {
                            writer.print("451 4.3.0 Mail transport unavailable\r\n");
                            writer.flush();
                            return;
                        }
                        from = extractAddress(line.substring(10));
                        writer.print("250 OK\r\n");
                        writer.flush();
                    } else if (upper.startsWith("RCPT TO:")) {
                        to = extractAddress(line.substring(8));
                        writer.print("250 OK\r\n");
                        writer.flush();
                    } else if (upper.equals("DATA")) {
                        writer.print("354 End data with <CR><LF>.<CR><LF>\r\n");
                        writer.flush();
                        ByteArrayOutputStream rawMessage = new ByteArrayOutputStream();
                        String dataLine;
                        while ((dataLine = reader.readLine()) != null) {
                            if (dataLine.equals(".")) {
                                break;
                            }
                            if (dataLine.startsWith("..")) {
                                dataLine = dataLine.substring(1);
                            }
                            rawMessage.write(dataLine.getBytes(StandardCharsets.UTF_8));
                            rawMessage.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        }
                        parseAndStoreMime(from, to, rawMessage.toByteArray());
                        writer.print("250 OK: queued\r\n");
                        writer.flush();
                    } else if (upper.startsWith("RSET") || upper.startsWith("NOOP")) {
                        writer.print("250 OK\r\n");
                        writer.flush();
                    } else if (upper.startsWith("QUIT")) {
                        writer.print("221 Bye\r\n");
                        writer.flush();
                        break;
                    } else {
                        writer.print("250 OK\r\n");
                        writer.flush();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        static String extractText(jakarta.mail.Part part) throws Exception {
            if (part.isMimeType("text/*")) {
                Object c = part.getContent();
                if (c instanceof String text) { return text; }
                throw new IllegalStateException("La parte MIME de texto no fue decodificada");
            }
            if (part.isMimeType("multipart/*")) {
                jakarta.mail.Multipart mp = (jakarta.mail.Multipart) part.getContent();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mp.getCount(); i++) {
                    sb.append(extractText(mp.getBodyPart(i))).append("\n");
                }
                return sb.toString();
            }
            if (part.isMimeType("message/rfc822")) {
                return extractText((jakarta.mail.Part) part.getContent());
            }
            return "";
        }

        private String extractAddress(String raw) {
            String trimmed = raw.trim();
            int start = trimmed.indexOf('<');
            int end = trimmed.indexOf('>');
            if (start >= 0 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
            return trimmed;
        }

        void parseAndStoreMime(String envelopeFrom, String envelopeTo, byte[] rawBytes) {
            try {
                MimeMessage mimeMessage = new MimeMessage(
                        Session.getDefaultInstance(new Properties()),
                        new ByteArrayInputStream(rawBytes)
                );
                String subject = mimeMessage.getSubject() != null ? mimeMessage.getSubject() : "";
                String body = extractText(mimeMessage);
                capturedEmails.add(new CapturedEmail(envelopeFrom, envelopeTo, subject, body));
            } catch (Exception e) {
                throw new IllegalStateException("No se pudo interpretar el correo MIME capturado", e);
            }
        }
    }
}
