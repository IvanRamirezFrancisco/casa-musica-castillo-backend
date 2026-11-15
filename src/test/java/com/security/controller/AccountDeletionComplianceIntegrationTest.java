package com.security.controller;

import com.security.entity.Role;
import com.security.entity.User;
import com.security.repository.RoleRepository;
import com.security.repository.UserRepository;
import com.security.security.JwtTokenProvider;
import com.security.security.UserPrincipal;
import com.security.service.EmailService;
import com.security.support.LocalPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.legal.deletion-intake-enabled=true",
        "app.legal.automatic-deletion-enabled=true",
        "app.mercadopago.enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AccountDeletionComplianceIntegrationTest extends LocalPostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private EmailService emailService;

    private final ObjectMapper json = new ObjectMapper();
    private final java.util.List<UUID> createdRequests = new java.util.ArrayList<>();
    private final java.util.List<Long> createdOrders = new java.util.ArrayList<>();
    private final java.util.List<Long> createdUsers = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID reqId : createdRequests) {
            jdbc.update("DELETE FROM auth.account_deletion_requests WHERE request_id = ?", reqId);
        }
        for (Long orderId : createdOrders) {
            jdbc.update("DELETE FROM sales.orders WHERE id = ?", orderId);
        }
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM auth.active_sessions WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM auth.refresh_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM auth.account_deletion_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM auth.account_deletion_requests WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM auth.user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM auth.users WHERE id = ?", userId);
        }
        createdRequests.clear();
        createdOrders.clear();
        createdUsers.clear();
        reset(emailService);
    }

    @Test
    void exposesPublicDeletionConfigurationAndAntiEnumeratingIntake() throws Exception {
        mvc.perform(get("/api/public/legal/deletion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.publicEmailRequestEnabled").value(true))
                .andExpect(jsonPath("$.automaticDeletionEnabled").value(true))
                .andExpect(jsonPath("$.tokenValidityMinutes").value(30));

        User user = createCustomer("public.flow@example.invalid");

        String missingBody = mvc.perform(post("/api/public/legal/deletion/request")
                        .with(req -> { req.setRemoteAddr("10.10.1.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "nobody.here@example.invalid"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andReturn().getResponse().getContentAsString();

        verifyNoInteractions(emailService);

        String existingBody = mvc.perform(post("/api/public/legal/deletion/request")
                        .with(req -> { req.setRemoteAddr("10.10.1.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail()))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(existingBody).get("message").asText())
                .isEqualTo(json.readTree(missingBody).get("message").asText());

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAccountDeletionConfirmationEmail(
                eq(user.getEmail()), eq(user.getFirstName()), tokenCaptor.capture());

        String rawToken = tokenCaptor.getValue();
        assertThat(rawToken).hasSizeGreaterThanOrEqualTo(32);

        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM auth.account_deletion_tokens WHERE user_id = ? AND used_at IS NULL",
                String.class, user.getId());
        assertThat(storedHash).isEqualTo(sha256Hex(rawToken)).isNotEqualTo(rawToken);
    }

    @Test
    void confirmsPublicSingleUseTokenRevokesSessionsAndCompletesEligibleAccountDeletion() throws Exception {
        User user = createCustomer("public.confirm@example.invalid");
        String bearer = issueSessionJwt(user);

        mvc.perform(post("/api/public/legal/deletion/request")
                        .with(req -> { req.setRemoteAddr("10.10.2.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail()))))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAccountDeletionConfirmationEmail(
                eq(user.getEmail()), anyString(), tokenCaptor.capture());
        String rawToken = tokenCaptor.getValue();

        mvc.perform(post("/api/public/legal/deletion/confirm")
                        .with(req -> { req.setRemoteAddr("10.10.2.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", rawToken, "confirmed", false))))
                .andExpect(status().isBadRequest());

        String receiptBody = mvc.perform(post("/api/public/legal/deletion/confirm")
                        .with(req -> { req.setRemoteAddr("10.10.2.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", rawToken, "confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode receipt = json.readTree(receiptBody);
        UUID requestId = UUID.fromString(receipt.get("requestId").asText());
        createdRequests.add(requestId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.users WHERE id = ?", Long.class, user.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.active_sessions WHERE user_id = ?", Long.class, user.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT initiation_channel FROM auth.account_deletion_requests WHERE request_id = ?",
                String.class, requestId)).isEqualTo("PUBLIC_EMAIL_TOKEN");

        mvc.perform(get("/api/users/me/deletion-requests").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/public/legal/deletion/confirm")
                        .with(req -> { req.setRemoteAddr("10.10.2.4"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", rawToken, "confirmed", true))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/public/legal/deletion/status")
                        .with(req -> { req.setRemoteAddr("10.10.2.5"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("requestId", requestId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void rejectsExpiredPublicTokenAndEnforcesRateLimits() throws Exception {
        User user = createCustomer("public.expired@example.invalid");

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/public/legal/deletion/request")
                            .with(req -> { req.setRemoteAddr("10.10.3.1"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", user.getEmail()))))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(post("/api/public/legal/deletion/request")
                        .with(req -> { req.setRemoteAddr("10.10.3.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail()))))
                .andExpect(status().isTooManyRequests());

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(3)).sendAccountDeletionConfirmationEmail(
                eq(user.getEmail()), anyString(), tokenCaptor.capture());
        String latestToken = tokenCaptor.getValue();

        jdbc.update("UPDATE auth.account_deletion_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '5 minutes' WHERE token_hash = ?",
                sha256Hex(latestToken));

        mvc.perform(post("/api/public/legal/deletion/confirm")
                        .with(req -> { req.setRemoteAddr("10.10.3.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", latestToken, "confirmed", true))))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.users WHERE id = ?", Long.class, user.getId())).isOne();
    }

    @Test
    void protectsActiveOrdersDuringReviewAndResolvesCompletedOrdersWithFiscalAnonymization() throws Exception {
        User customer = createCustomer("order.retention@example.invalid");
        User admin = createAdmin("admin.reviewer@example.invalid");
        String customerBearer = issueSessionJwt(customer);
        String adminBearer = issueSessionJwt(admin);

        Long orderId = jdbc.queryForObject("""
                INSERT INTO sales.orders (
                    order_number, user_id, subtotal, tax, shipping, discount, total,
                    status, shipping_status, payment_status, shipping_address, notes, created_at
                )
                VALUES (?, ?, 1000.00, 160.00, 0.00, 0.00, 1160.00,
                        'PENDING', 'PENDING', 'PENDING', 'Calle Morelos 18, Huejutla', 'Nota cliente', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, "ORD-DEL-" + UUID.randomUUID().toString().substring(0, 8), customer.getId());
        createdOrders.add(orderId);

        String requestBody = mvc.perform(post("/api/users/me/deletion-requests")
                        .header("Authorization", "Bearer " + customerBearer)
                        .with(req -> { req.setRemoteAddr("10.10.4.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", "Synthetic@9zN", "confirmed", true))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andReturn().getResponse().getContentAsString();

        UUID requestId = UUID.fromString(json.readTree(requestBody).get("requestId").asText());
        createdRequests.add(requestId);

        mvc.perform(get("/api/admin/account-deletion-requests").header("Authorization", "Bearer " + customerBearer))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/account-deletion-requests?status=REVIEW_REQUIRED")
                        .header("Authorization", "Bearer " + adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.requestId == '" + requestId + "')].hasBlockingActiveOrders").value(org.hamcrest.Matchers.contains(true)));

        mvc.perform(post("/api/admin/account-deletion-requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("resolutionNotes", "Intento prematuro"))))
                .andExpect(status().isConflict());

        jdbc.update("UPDATE sales.orders SET status = 'COMPLETED', payment_status = 'PAID', shipping_status = 'DELIVERED' WHERE id = ?", orderId);

        mvc.perform(post("/api/admin/account-deletion-requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("resolutionNotes", "Pedido concluido; anonimización fiscal aplicada."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.users WHERE id = ?", Long.class, customer.getId())).isZero();

        Map<String, Object> retainedOrder = jdbc.queryForMap(
                "SELECT user_id, shipping_address, notes, total FROM sales.orders WHERE id = ?", orderId);
        assertThat(retainedOrder.get("user_id")).isNull();
        assertThat(retainedOrder.get("shipping_address")).isEqualTo("[DATOS PERSONALES ELIMINADOS POR SOLICITUD DEL TITULAR]");
        assertThat(retainedOrder.get("notes")).isNull();
        assertThat(retainedOrder.get("total").toString()).startsWith("1160");
    }

    private User createCustomer(String email) {
        Role customerRole = roles.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("ROLE_USER requerido"));
        return saveUser(email, true, Set.of(customerRole));
    }

    private User createAdmin(String email) {
        Role adminRole = roles.findByName("ROLE_ADMIN")
                .orElseThrow(() -> new IllegalStateException("ROLE_ADMIN requerido"));
        return saveUser(email, false, Set.of(adminRole));
    }

    private User saveUser(String email, boolean isCustomer, Set<Role> assignedRoles) {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User u = new User();
        u.setUsername("deltest_" + uniqueSuffix);
        u.setFirstName("Synthetic");
        u.setLastName("Deletion");
        u.setEmail(uniqueSuffix + "." + email);
        u.setPassword(passwordEncoder.encode("Synthetic@9zN"));
        u.setEnabled(true);
        u.setAccountNonExpired(true);
        u.setAccountNonLocked(true);
        u.setCredentialsNonExpired(true);
        u.setTwoFactorEnabled(false);
        u.setGoogleAuthEnabled(false);
        u.setEmailEnabled(false);
        u.setBackupCodesEnabled(false);
        u.setIsCustomer(isCustomer);
        u.setRoles(assignedRoles);
        User saved = users.save(u);
        createdUsers.add(saved.getId());
        return saved;
    }

    private String issueSessionJwt(User user) {
        User hydrated = users.findByIdWithRolesAndPermissions(user.getId())
                .orElseThrow(() -> new IllegalStateException("Usuario no encontrado"));
        UserPrincipal principal = UserPrincipal.create(hydrated);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("User-Agent", "JUnit-DeletionTest");
        return jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()), req);
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
