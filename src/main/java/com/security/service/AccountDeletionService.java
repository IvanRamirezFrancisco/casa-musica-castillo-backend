package com.security.service;

import com.security.exception.AuthFlowException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountDeletionService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String SELECT_USER_EMAIL_FOR_UPDATE = "SELECT email FROM auth.users WHERE id = ? FOR UPDATE";
    private static final String COL_USER_ID = "user_id";
    private static final String COL_STATUS = "status";
    private static final String ANONYMIZED_ORDER_MARKER = "[DATOS PERSONALES ELIMINADOS POR SOLICITUD DEL TITULAR]";
    private static final String PUBLIC_REQUEST_MESSAGE =
            "Si el correo está asociado a una cuenta registrada, enviaremos un enlace de verificación con vigencia de 30 minutos para confirmar la eliminación.";

    public record Receipt(UUID requestId, String status, OffsetDateTime receivedAt, OffsetDateTime completedAt) {}

    public record PublicInitiationResponse(boolean accepted, String message) {}

    public record AdminRequestSummary(
            UUID requestId,
            String status,
            OffsetDateTime receivedAt,
            OffsetDateTime completedAt,
            Long userId,
            String userEmail,
            String initiationChannel,
            String reviewReason,
            boolean hasBlockingActiveOrders,
            OffsetDateTime reviewedAt,
            String resolutionNotes
    ) {}

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final EmailService emailService;
    private final boolean intakeEnabled;
    private final boolean automaticDeletion;

    public AccountDeletionService(JdbcTemplate jdbc, UserService users, boolean automaticDeletion) {
        this(jdbc, users, null, false, automaticDeletion);
    }

    @Autowired
    public AccountDeletionService(
            JdbcTemplate jdbc,
            UserService users,
            @Autowired(required = false) EmailService emailService,
            @Value("${app.legal.deletion-intake-enabled:false}") boolean intakeEnabled,
            @Value("${app.legal.automatic-deletion-enabled:false}") boolean automaticDeletion) {
        this.jdbc = jdbc;
        this.users = users;
        this.emailService = emailService;
        this.intakeEnabled = intakeEnabled;
        this.automaticDeletion = automaticDeletion;
    }

    @Transactional
    public Receipt request(long userId, String currentPassword) {
        return request(userId, currentPassword, null);
    }

    @Transactional
    public Receipt request(long userId, String currentPassword, String reauthToken) {
        String email = jdbc.query(SELECT_USER_EMAIL_FOR_UPDATE,
                (row, index) -> row.getString(1), userId).stream().findFirst().orElseThrow(AccountDeletionService::missing);
        java.util.HashMap<String, String> stepUp = new java.util.HashMap<>();
        if (currentPassword != null) stepUp.put("currentPassword", currentPassword);
        if (reauthToken != null) stepUp.put("reauthToken", reauthToken);
        users.requireStepUp(userId, stepUp);
        return processVerifiedRequest(userId, email, "AUTHENTICATED", false);
    }

    @Transactional
    public PublicInitiationResponse requestPublicByEmail(String rawEmail, String requestIp) {
        if (!intakeEnabled) {
            throw new AuthFlowException(HttpStatus.CONFLICT, "La recepción de solicitudes de eliminación aún no está disponible.");
        }
        String normalizedEmail = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty()) {
            return new PublicInitiationResponse(true, PUBLIC_REQUEST_MESSAGE);
        }

        List<UserRow> matches = jdbc.query(
                "SELECT id, email, first_name FROM auth.users WHERE lower(email) = ? ORDER BY id ASC LIMIT 1 FOR UPDATE",
                (row, index) -> new UserRow(row.getLong("id"), row.getString("email"), row.getString("first_name")),
                normalizedEmail);

        if (matches.isEmpty()) {
            return new PublicInitiationResponse(true, PUBLIC_REQUEST_MESSAGE);
        }

        UserRow account = matches.get(0);
        jdbc.update("UPDATE auth.account_deletion_tokens SET used_at = CURRENT_TIMESTAMP WHERE user_id = ? AND used_at IS NULL",
                account.id());

        String rawToken = generateRawToken();
        String tokenHash = sha256Hex(rawToken);
        jdbc.update("""
                INSERT INTO auth.account_deletion_tokens (id, user_id, token_hash, expires_at, request_ip)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '30 minutes', ?)
                """, UUID.randomUUID(), account.id(), tokenHash, truncate(requestIp, 64));

        if (emailService == null) {
            throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE, "El servicio de correo no está disponible temporalmente.");
        }
        emailService.sendAccountDeletionConfirmationEmail(account.email(), account.firstName(), rawToken);
        return new PublicInitiationResponse(true, PUBLIC_REQUEST_MESSAGE);
    }

    @Transactional
    public Receipt confirmPublicToken(String rawToken) {
        if (!intakeEnabled) {
            throw new AuthFlowException(HttpStatus.CONFLICT, "La recepción de solicitudes de eliminación aún no está disponible.");
        }
        String tokenHash = sha256Hex(rawToken == null ? "" : rawToken.trim());
        List<TokenRow> tokens = jdbc.query("""
                SELECT id, user_id, expires_at, used_at
                FROM auth.account_deletion_tokens
                WHERE token_hash = ?
                FOR UPDATE
                """,
                (row, index) -> new TokenRow(
                        row.getObject("id", UUID.class),
                        row.getLong(COL_USER_ID),
                        row.getObject("expires_at", OffsetDateTime.class),
                        row.getObject("used_at", OffsetDateTime.class)),
                tokenHash);

        if (tokens.isEmpty()) {
            throw invalidOrExpiredToken();
        }
        TokenRow token = tokens.get(0);
        if (token.usedAt() != null || !token.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw invalidOrExpiredToken();
        }

        jdbc.update("UPDATE auth.account_deletion_tokens SET used_at = CURRENT_TIMESTAMP WHERE id = ?", token.id());

        String email = jdbc.query(SELECT_USER_EMAIL_FOR_UPDATE,
                (row, index) -> row.getString(1), token.userId()).stream().findFirst().orElseThrow(AccountDeletionService::missing);

        return processVerifiedRequest(token.userId(), email, "PUBLIC_EMAIL_TOKEN", true);
    }

    public List<AdminRequestSummary> listRequestsForAdmin(String statusFilter) {
        boolean filterByStatus = statusFilter != null && !statusFilter.isBlank();
        String baseSql = """
                SELECT r.request_id,
                       r.status,
                       r.received_at,
                       r.completed_at,
                       r.user_id,
                       u.email AS user_email,
                       r.initiation_channel,
                       r.review_reason,
                       r.reviewed_at,
                       r.resolution_notes,
                       CASE WHEN r.user_id IS NOT NULL THEN (
                           SELECT EXISTS (
                               SELECT 1 FROM sales.orders o
                               WHERE o.user_id = r.user_id
                                 AND upper(o.status) NOT IN ('DELIVERED', 'COMPLETED', 'CANCELLED', 'REFUNDED')
                           )
                           OR EXISTS (
                               SELECT 1 FROM sales.payment_proofs pp
                               JOIN sales.orders o ON o.id = pp.order_id
                               WHERE pp.uploaded_by = r.user_id
                                 AND upper(pp.status) IN ('PENDING', 'UNDER_REVIEW')
                                 AND upper(o.status) NOT IN ('CANCELLED', 'REFUNDED')
                           )
                       ) ELSE false END AS has_blocking_active_orders
                FROM auth.account_deletion_requests r
                LEFT JOIN auth.users u ON u.id = r.user_id
                """;
        String sql = filterByStatus
                ? baseSql + " WHERE upper(r.status) = upper(?) ORDER BY r.received_at DESC"
                : baseSql + " ORDER BY r.received_at DESC";

        return filterByStatus
                ? jdbc.query(sql, AccountDeletionService::mapAdminSummary, statusFilter.trim())
                : jdbc.query(sql, AccountDeletionService::mapAdminSummary);
    }

    @Transactional
    public Receipt resolveRequestAsAdmin(UUID requestId, long adminUserId, String resolutionNotes) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_id, user_id, status FROM auth.account_deletion_requests WHERE request_id = ? FOR UPDATE",
                requestId);
        if (rows.isEmpty()) {
            throw missing();
        }
        Map<String, Object> req = rows.get(0);
        String currentStatus = String.valueOf(req.get(COL_STATUS));
        if ("COMPLETED".equals(currentStatus)) {
            return track(requestId);
        }

        Number rawUserId = (Number) req.get(COL_USER_ID);
        if (rawUserId == null) {
            jdbc.update("""
                    UPDATE auth.account_deletion_requests
                    SET status = 'COMPLETED',
                        completed_at = CURRENT_TIMESTAMP,
                        reviewed_by = ?,
                        reviewed_at = CURRENT_TIMESTAMP,
                        resolution_notes = ?
                    WHERE request_id = ?
                    """, adminUserId, truncate(resolutionNotes, 500), requestId);
            return track(requestId);
        }

        long userId = rawUserId.longValue();
        String email = jdbc.query(SELECT_USER_EMAIL_FOR_UPDATE,
                (row, index) -> row.getString(1), userId).stream().findFirst().orElseThrow(AccountDeletionService::missing);

        boolean hasStaffRole = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM auth.user_roles ur
                    JOIN auth.roles r ON r.id = ur.role_id
                    WHERE ur.user_id = ? AND r.name <> 'ROLE_USER'
                )
                """, Boolean.class, userId));
        if (hasStaffRole) {
            throw new AuthFlowException(HttpStatus.CONFLICT,
                    "No se puede aprobar automáticamente la eliminación de una cuenta con rol administrativo o de personal.");
        }

        if (hasBlockingCommercialOperations(userId)) {
            throw new AuthFlowException(HttpStatus.CONFLICT,
                    "La cuenta tiene pedidos en curso o comprobantes de pago pendientes de revisión. Concluye esas operaciones antes de eliminar la cuenta.");
        }

        anonymizeAndDetachRetainedRecords(userId);
        eraseAccount(userId, email);

        jdbc.update("""
                UPDATE auth.account_deletion_requests
                SET status = 'COMPLETED',
                    completed_at = CURRENT_TIMESTAMP,
                    reviewed_by = ?,
                    reviewed_at = CURRENT_TIMESTAMP,
                    resolution_notes = ?
                WHERE request_id = ?
                """, adminUserId, truncate(resolutionNotes, 500), requestId);

        return track(requestId);
    }

    public Receipt ownRequest(long userId) {
        return lookup("SELECT request_id, status, received_at, completed_at FROM auth.account_deletion_requests WHERE user_id = ?", userId);
    }

    public Receipt track(UUID requestId) {
        return lookup("SELECT request_id, status, received_at, completed_at FROM auth.account_deletion_requests WHERE request_id = ?", requestId);
    }

    private Receipt processVerifiedRequest(long userId, String email, String initiationChannel, boolean revokeActiveSessionsNow) {
        jdbc.update("""
                INSERT INTO auth.account_deletion_requests (request_id, user_id, initiation_channel)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET initiation_channel = EXCLUDED.initiation_channel
                """, UUID.randomUUID(), userId, initiationChannel);

        jdbc.update("DELETE FROM auth.refresh_tokens WHERE user_id = ?", userId);
        if (revokeActiveSessionsNow) {
            jdbc.update("DELETE FROM auth.active_sessions WHERE user_id = ?", userId);
        }

        Receipt receipt = ownRequest(userId);
        if (automaticDeletion) {
            String reviewReason = classifyReviewReason(userId, email);
            if (reviewReason != null) {
                jdbc.update("""
                        UPDATE auth.account_deletion_requests
                        SET status = 'REVIEW_REQUIRED', review_reason = ?
                        WHERE request_id = ?
                        """, reviewReason, receipt.requestId());
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

    private Receipt lookup(String sql, Object identifier) {
        return jdbc.query(sql, (row, index) -> new Receipt(row.getObject("request_id", UUID.class),
                row.getString(COL_STATUS), row.getObject("received_at", OffsetDateTime.class),
                row.getObject("completed_at", OffsetDateTime.class)), identifier)
                .stream().findFirst().orElseThrow(AccountDeletionService::missing);
    }

    private boolean hasBlockingCommercialOperations(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM sales.orders
                    WHERE user_id = ?
                      AND upper(status) NOT IN ('DELIVERED', 'COMPLETED', 'CANCELLED', 'REFUNDED')
                )
                OR EXISTS (
                    SELECT 1 FROM sales.payment_proofs pp
                    JOIN sales.orders o ON o.id = pp.order_id
                    WHERE pp.uploaded_by = ?
                      AND upper(pp.status) IN ('PENDING', 'UNDER_REVIEW')
                      AND upper(o.status) NOT IN ('CANCELLED', 'REFUNDED')
                )
                """, Boolean.class, userId, userId));
    }

    private String classifyReviewReason(long userId, String email) {
        if (hasBlockingCommercialOperations(userId)) {
            return "ACTIVE_ORDERS_OR_PENDING_PAYMENTS";
        }
        boolean hasCommercialHistory = Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH account AS (SELECT CAST(? AS bigint) AS id, CAST(? AS text) AS email)
                SELECT EXISTS (SELECT 1 FROM sales.orders, account WHERE user_id = account.id)
                    OR EXISTS (SELECT 1 FROM sales.coupon_usage c, account
                        WHERE c.user_id = account.id OR c.cart_id IN
                            (SELECT id FROM sales.shopping_carts WHERE user_id = account.id))
                    OR EXISTS (SELECT 1 FROM sales.payment_proofs, account
                        WHERE uploaded_by = account.id OR reviewed_by = account.id)
                    OR EXISTS (SELECT 1 FROM sales.payments, account
                        WHERE created_by_user_id = account.id OR lower(payer_email) = lower(account.email))
                    OR EXISTS (SELECT 1 FROM sales.order_timeline_events, account WHERE actor_user_id = account.id)
                """, Boolean.class, userId, email));
        if (hasCommercialHistory) {
            return "ORDERS_OR_COMMERCIAL_HISTORY";
        }
        boolean hasStaffAssociations = Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH account AS (SELECT CAST(? AS bigint) AS id, CAST(? AS text) AS email)
                SELECT EXISTS (SELECT 1 FROM auth.user_roles ur JOIN auth.roles r ON r.id = ur.role_id, account
                        WHERE ur.user_id = account.id AND r.name <> 'ROLE_USER')
                    OR EXISTS (SELECT 1 FROM sales.bank_transfer_settings, account WHERE updated_by = account.id)
                    OR EXISTS (SELECT 1 FROM catalog.product_images, account WHERE uploaded_by_id = account.id)
                    OR EXISTS (SELECT 1 FROM catalog.product_price_history, account WHERE changed_by = account.id)
                    OR EXISTS (SELECT 1 FROM ops.system_automations, account WHERE updated_by = account.id)
                    OR EXISTS (SELECT 1 FROM security.security_settings, account WHERE updated_by = account.id)
                    OR EXISTS (SELECT 1 FROM security.staff_invitations, account
                        WHERE invited_by = account.id OR lower(security.staff_invitations.email) = lower(account.email))
                """, Boolean.class, userId, email));
        if (hasStaffAssociations) {
            return "STAFF_OR_ADMIN_ROLE";
        }
        boolean hasAuditRecords = Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH account AS (SELECT CAST(? AS bigint) AS id)
                SELECT EXISTS (SELECT 1 FROM security.audit_logs, account
                    WHERE performed_by_user_id = account.id
                        OR (upper(entity_type) = 'USER' AND entity_id = account.id))
                """, Boolean.class, userId));
        return hasAuditRecords ? "AUDIT_OR_SECURITY_RECORDS" : null;
    }

    private void anonymizeAndDetachRetainedRecords(long userId) {
        jdbc.update("""
                UPDATE sales.orders
                SET user_id = NULL,
                    shipping_address = ?,
                    billing_address = NULL,
                    customer_notes = NULL,
                    notes = NULL,
                    picked_up_by_name = NULL
                WHERE user_id = ?
                """, ANONYMIZED_ORDER_MARKER, userId);

        jdbc.update("UPDATE sales.coupon_usage SET user_id = NULL WHERE user_id = ?", userId);
        jdbc.update("UPDATE sales.payments SET created_by_user_id = NULL WHERE created_by_user_id = ?", userId);
        jdbc.update("UPDATE sales.payment_proofs SET reviewed_by = NULL WHERE reviewed_by = ?", userId);
        jdbc.update("UPDATE sales.order_timeline_events SET actor_user_id = NULL WHERE actor_user_id = ?", userId);

        jdbc.update("""
                UPDATE security.audit_logs
                SET performed_by_user_id = NULL,
                    performed_by = '[ELIMINADO]',
                    old_values = NULL,
                    new_values = NULL,
                    ip_address = NULL,
                    user_agent = NULL
                WHERE performed_by_user_id = ?
                   OR (upper(entity_type) = 'USER' AND entity_id = ?)
                """, userId, userId);
    }

    private void eraseAccount(long userId, String email) {
        jdbc.update("DELETE FROM auth.active_sessions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.refresh_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.external_identities WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.google_auth_flows WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.backup_codes WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.two_factor_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.verification_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth.account_deletion_tokens WHERE user_id = ?", userId);
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

    private static AdminRequestSummary mapAdminSummary(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        Number rawUserId = (Number) row.getObject(COL_USER_ID);
        return new AdminRequestSummary(
                row.getObject("request_id", UUID.class),
                row.getString(COL_STATUS),
                row.getObject("received_at", OffsetDateTime.class),
                row.getObject("completed_at", OffsetDateTime.class),
                rawUserId != null ? rawUserId.longValue() : null,
                row.getString("user_email"),
                row.getString("initiation_channel"),
                row.getString("review_reason"),
                row.getBoolean("has_blocking_active_orders"),
                row.getObject("reviewed_at", OffsetDateTime.class),
                row.getString("resolution_notes")
        );
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private record UserRow(long id, String email, String firstName) {}

    private record TokenRow(UUID id, long userId, OffsetDateTime expiresAt, OffsetDateTime usedAt) {}

    private static AuthFlowException missing() {
        return new AuthFlowException(HttpStatus.NOT_FOUND, "No hay una solicitud registrada.");
    }

    private static AuthFlowException invalidOrExpiredToken() {
        return new AuthFlowException(HttpStatus.BAD_REQUEST, "El enlace de confirmación no es válido, ya fue utilizado o ha expirado.");
    }
}
