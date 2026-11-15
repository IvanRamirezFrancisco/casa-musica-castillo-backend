package com.security.controller;

import com.security.entity.User;
import com.security.entity.ShoppingCart;
import com.security.repository.ShoppingCartRepository;
import com.security.repository.UserRepository;
import com.security.security.UserPrincipal;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.legal.deletion-intake-enabled=true", "app.legal.automatic-deletion-enabled=true"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class AccountDeletionIntegrationTest extends LocalPostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ShoppingCartRepository carts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @MockitoBean com.security.config.MercadoPagoConfiguration payments;

    @Test
    void deletesEligibleAccountAndProvidesAnAnonymousConfirmation() throws Exception {
        User owner = create("synthetic_delete_owner");
        User other = create("synthetic_delete_other");
        jdbc.update("INSERT INTO auth.login_factor_limits (user_id, window_start) VALUES (?, CURRENT_TIMESTAMP)", owner.getId());
        ShoppingCart cart = new ShoppingCart();
        cart.setUser(owner);
        carts.saveAndFlush(cart);
        String response = request(owner, "COMPLETED");
        String requestId = json.readTree(response).get("requestId").asText();

        assertEquals(0, count("auth.users", "id", owner.getId()));
        assertEquals(0, count("auth.login_factor_limits", "user_id", owner.getId()));
        assertEquals(0, count("sales.shopping_carts", "user_id", owner.getId()));
        assertEquals(1, count("auth.users", "id", other.getId()));
        mvc.perform(post("/api/public/legal/deletion/status").contentType("application/json")
                .content("{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void keepsAccountsWithUnclassifiedAuditRecordsForReview() throws Exception {
        User owner = create("synthetic_delete_review");
        jdbc.update("""
                INSERT INTO security.audit_logs
                    (action, entity_type, entity_id, performed_by, created_at, event_description, event_type, is_success)
                VALUES ('UPDATE', 'USER', ?, 'synthetic', CURRENT_TIMESTAMP, 'Synthetic event', 'TEST', true)
                """, owner.getId());
        request(owner, "REVIEW_REQUIRED");
        assertEquals(1, count("auth.users", "id", owner.getId()));
    }

    private String request(User account, String outcome) throws Exception {
        return mvc.perform(post("/api/users/me/deletion-requests").with(user(UserPrincipal.create(account)))
                        .contentType("application/json")
                        .content("{\"confirmed\":true,\"currentPassword\":\"ClaveSegura2026!#\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value(outcome))
                .andReturn().getResponse().getContentAsString();
    }

    private int count(String table, String column, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }

    private User create(String name) {
        User user = new User(name, "Synthetic", "Privacy", name + "@example.invalid",
                passwords.encode("ClaveSegura2026!#"));
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }
}
