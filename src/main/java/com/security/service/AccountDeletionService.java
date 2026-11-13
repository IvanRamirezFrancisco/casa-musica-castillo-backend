package com.security.service;

import com.security.exception.AuthFlowException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountDeletionService {
    public record Receipt(UUID requestId, String status, OffsetDateTime receivedAt, OffsetDateTime completedAt) {}

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final boolean automaticDeletion;

    public AccountDeletionService(JdbcTemplate jdbc, UserService users,
            @Value("${app.legal.automatic-deletion-enabled:false}") boolean automaticDeletion) {
        this.jdbc = jdbc;
        this.users = users;
        this.automaticDeletion = automaticDeletion;
    }

    @Transactional
    public Receipt request(long userId, String currentPassword) {
        String email = jdbc.query("SELECT email FROM auth.users WHERE id = ? FOR UPDATE",
                (row, index) -> row.getString(1), userId).stream().findFirst().orElseThrow(AccountDeletionService::missing);
        users.requireStepUp(userId, Map.of("currentPassword", currentPassword));
        jdbc.update("""
                INSERT INTO auth.account_deletion_requests (request_id, user_id) VALUES (?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, UUID.randomUUID(), userId);
        Receipt receipt = ownRequest(userId);
        if (automaticDeletion) {
            if (requiresReview(userId, email)) {
                jdbc.update("UPDATE auth.account_deletion_requests SET status = 'REVIEW_REQUIRED' WHERE request_id = ?",
                        receipt.requestId());
            } else {
                eraseAccount(userId, email);
                jdbc.update("""
                        UPDATE auth.account_deletion_requests SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                        WHERE request_id = ?
                        """, receipt.requestId());
            }
        }
        return track(receipt.requestId());
    }

    public Receipt ownRequest(long userId) {
        return lookup("SELECT request_id, status, received_at, completed_at FROM auth.account_deletion_requests WHERE user_id = ?", userId);
    }

    public Receipt track(UUID requestId) {
        return lookup("SELECT request_id, status, received_at, completed_at FROM auth.account_deletion_requests WHERE request_id = ?", requestId);
    }

    private Receipt lookup(String sql, Object identifier) {
        return jdbc.query(sql, (row, index) -> new Receipt(row.getObject("request_id", UUID.class),
                row.getString("status"), row.getObject("received_at", OffsetDateTime.class),
                row.getObject("completed_at", OffsetDateTime.class)), identifier)
                .stream().findFirst().orElseThrow(AccountDeletionService::missing);
    }

    private boolean requiresReview(long userId, String email) {
        // These associations may require a decision about retention or an external copy.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH account AS (SELECT CAST(? AS bigint) AS id, CAST(? AS text) AS email)
                SELECT EXISTS (SELECT 1 FROM sales.orders, account WHERE user_id = account.id)
                    OR EXISTS (SELECT 1 FROM sales.coupon_usage c, account
                        WHERE c.user_id = account.id OR c.cart_id IN
                            (SELECT id FROM sales.shopping_carts WHERE user_id = account.id))
                    OR EXISTS (SELECT 1 FROM auth.user_roles ur JOIN auth.roles r ON r.id = ur.role_id, account
                        WHERE ur.user_id = account.id AND r.name <> 'ROLE_USER')
                    OR EXISTS (SELECT 1 FROM sales.payment_proofs, account
                        WHERE uploaded_by = account.id OR reviewed_by = account.id)
                    OR EXISTS (SELECT 1 FROM sales.payments, account
                        WHERE created_by_user_id = account.id OR lower(payer_email) = lower(account.email))
                    OR EXISTS (SELECT 1 FROM sales.order_timeline_events, account WHERE actor_user_id = account.id)
                    OR EXISTS (SELECT 1 FROM sales.bank_transfer_settings, account WHERE updated_by = account.id)
                    OR EXISTS (SELECT 1 FROM catalog.product_images, account WHERE uploaded_by_id = account.id)
                    OR EXISTS (SELECT 1 FROM ops.system_automations, account WHERE updated_by = account.id)
                    OR EXISTS (SELECT 1 FROM security.staff_invitations, account
                        WHERE invited_by = account.id OR lower(security.staff_invitations.email) = lower(account.email))
                    OR EXISTS (SELECT 1 FROM security.audit_logs, account
                        WHERE performed_by_user_id = account.id
                            OR (upper(entity_type) = 'USER' AND entity_id = account.id))
                """, Boolean.class, userId, email));
    }

    private void eraseAccount(long userId, String email) {
        jdbc.update("DELETE FROM auth.active_sessions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.refresh_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.backup_codes WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.two_factor_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.verification_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM security.password_reset_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM security.login_attempts WHERE lower(email) = lower(?)", email);
        jdbc.update("DELETE FROM security.password_recovery_attempts WHERE lower(email) = lower(?)", email);
        jdbc.update("DELETE FROM customer.addresses WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM sales.wishlists WHERE user_id = ?", userId);
        jdbc.update("""
                DELETE FROM sales.cart_items WHERE cart_id IN (SELECT id FROM sales.shopping_carts WHERE user_id = ?)
                """, userId);
        jdbc.update("DELETE FROM sales.shopping_carts WHERE user_id = ?", userId);
        jdbc.update("""
                DELETE FROM catalog.review_helpfulness WHERE user_id = ?
                    OR review_id IN (SELECT id FROM catalog.product_reviews WHERE user_id = ?)
                """, userId, userId);
        jdbc.update("DELETE FROM catalog.product_reviews WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.user_roles WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.users WHERE id = ?", userId);
    }

    private static AuthFlowException missing() {
        return new AuthFlowException(HttpStatus.NOT_FOUND, "No hay una solicitud registrada.");
    }
}
