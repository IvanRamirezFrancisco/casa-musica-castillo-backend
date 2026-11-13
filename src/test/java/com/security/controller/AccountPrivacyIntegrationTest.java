package com.security.controller;

import com.security.dto.request.RegisterRequest;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.repository.UserRepository;
import com.security.security.UserPrincipal;
import com.security.service.RegistrationLegalPolicy;
import com.security.support.LocalPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.legal.deletion-intake-enabled=true")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class AccountPrivacyIntegrationTest extends LocalPostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @MockitoBean com.security.config.MercadoPagoConfiguration payments;

    @Test
    void onlyOwnerWithPasswordCanSubmitAndRetrieveTheSameReceipt() throws Exception {
        User first = create("deletion_first");
        User second = create("deletion_second");
        String path = "/api/users/me/deletion-requests";
        String body = """
                {"confirmed":true,"currentPassword":"ClaveSegura2026!#"}
                """;
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user(UserPrincipal.create(first))).contentType("application/json")
                .content("{\"confirmed\":true,\"currentPassword\":\"bad-password\"}"))
                .andExpect(status().isBadRequest());
        var request = post(path).with(user(UserPrincipal.create(first))).contentType("application/json").content(body);
        String firstResponse = mvc.perform(request).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED")).andReturn().getResponse().getContentAsString();
        mvc.perform(request).andExpect(status().isAccepted()).andExpect(content().json(firstResponse));
        mvc.perform(get(path).with(user(UserPrincipal.create(first)))).andExpect(status().isOk())
                .andExpect(content().json(firstResponse));
        mvc.perform(get(path).with(user(UserPrincipal.create(second)))).andExpect(status().isNotFound());
        assertTrue(users.findById(first.getId()).orElseThrow().getEnabled());
    }

    @Test
    void bothCurrentDocumentsAreRequiredAndRecordedWhenConfigured() {
        var legal = new RegistrationLegalPolicy(jdbc, "terms-test", "privacy-test");
        assertThrows(AuthFlowException.class, () -> legal.validate(null));
        assertThrows(AuthFlowException.class, () -> legal.validate(
                new RegisterRequest.LegalAcceptance(true, false, "terms-test", "privacy-test")));
        assertThrows(AuthFlowException.class, () -> legal.validate(
                new RegisterRequest.LegalAcceptance(false, true, "terms-test", "privacy-test")));
        assertThrows(AuthFlowException.class, () -> legal.validate(
                new RegisterRequest.LegalAcceptance(true, true, "older", "privacy-test")));
        assertDoesNotThrow(() -> legal.validate(
                new RegisterRequest.LegalAcceptance(true, true, "terms-test", "privacy-test")));
        User first = create("consent_record");
        legal.record(first.getId());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM auth.registration_legal_acceptances
                WHERE user_id = ? AND terms_version = 'terms-test' AND privacy_version = 'privacy-test'
                  AND recorded_at IS NOT NULL
                """, Integer.class, first.getId()));
    }

    private User create(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@example.invalid");
        user.setFirstName("Synthetic");
        user.setLastName("Privacy");
        user.setEnabled(true);
        user.setPassword(passwords.encode("ClaveSegura2026!#"));
        return users.saveAndFlush(user);
    }
}
