package com.security.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleLinkSchemaUpgradeTest {
    private static final String ADMIN_URL = "jdbc:postgresql://127.0.0.1:55439/postgres";
    private static final String DB_NAME = "google_link_upgrade_test";
    private static final String DB_URL = "jdbc:postgresql://127.0.0.1:55439/" + DB_NAME;

    @Test
    void upgradesV61ToV62PreservingLinkedIdentitiesAndAddingConfirmAndSecurityMailPurposes() {
        var adminJdbc = new JdbcTemplate(new DriverManagerDataSource(ADMIN_URL, "auth_test", "synthetic-test-only"));
        adminJdbc.execute("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
        adminJdbc.execute("CREATE DATABASE " + DB_NAME);

        try {
            var ds = new DriverManagerDataSource(DB_URL, "auth_test", "synthetic-test-only");
            var jdbc = new JdbcTemplate(ds);

            var v61 = Flyway.configure().dataSource(ds)
                    .schemas("public", "auth", "security", "catalog", "sales", "customer", "ops", "analytics")
                    .initSql("SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics")
                    .locations("classpath:db/test-baseline", "classpath:db/migration")
                    .target("61")
                    .load();
            v61.migrate();

            jdbc.update("""
                    INSERT INTO auth.users(id, username, first_name, last_name, email, password, enabled,
                        account_non_expired, account_non_locked, credentials_non_expired, two_factor_enabled,
                        google_auth_enabled, email_enabled, backup_codes_enabled, is_customer, created_at)
                    VALUES (1, 'v61.linked', 'Existing', 'User', 'existing.v61@example.invalid', 'synthetic-hash', true,
                        true, true, true, false, false, false, false, true, now())
                    """);
            jdbc.update("""
                    INSERT INTO auth.external_identities(user_id, provider, provider_subject, provider_email, email_verified, linked_at)
                    VALUES (1, 'GOOGLE', 'sub-v61-existing', 'existing.v61@example.invalid', true, now())
                    """);
            jdbc.update("""
                    INSERT INTO auth.google_auth_flows(token_hash, flow_type, user_id, session_jti, provider, purpose, created_at, expires_at)
                    VALUES (?, 'REAUTH', 1, 'jti-v61', 'GOOGLE', 'SECURITY_STEP_UP', now(), now() + interval '5 minutes')
                    """, "a".repeat(64));
            String opId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO auth.security_mail_outbox(operation_id, purpose, user_id, reset_token_id, state, payload, created_at, expires_at)
                    VALUES (?, 'PASSWORD_CHANGED', 1, NULL, 'ACCEPTED', NULL, now(), now() + interval '1 day')
                    """, opId);

            var originalIdentity = jdbc.queryForMap(
                    "SELECT user_id, provider, provider_subject, provider_email, email_verified FROM auth.external_identities WHERE user_id = 1");

            var v62 = Flyway.configure().dataSource(ds)
                    .schemas("public", "auth", "security", "catalog", "sales", "customer", "ops", "analytics")
                    .initSql("SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics")
                    .locations("classpath:db/test-baseline", "classpath:db/migration")
                    .target("62")
                    .load();
            assertThat(v62.migrate().migrationsExecuted).isEqualTo(1);
            v62.validate();

            assertThat(jdbc.queryForMap(
                    "SELECT user_id, provider, provider_subject, provider_email, email_verified FROM auth.external_identities WHERE user_id = 1"))
                    .isEqualTo(originalIdentity);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.google_auth_flows WHERE flow_type = 'REAUTH'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.security_mail_outbox WHERE purpose = 'PASSWORD_CHANGED'", Integer.class))
                    .isEqualTo(1);

            jdbc.update("""
                    INSERT INTO auth.google_auth_flows(
                        token_hash, flow_type, user_id, session_jti, provider, provider_subject, email, purpose, created_at, expires_at)
                    VALUES (?, 'LINK_CONFIRM', 1, 'jti-v62', 'GOOGLE', 'sub-v62', 'new.google@example.invalid', 'LINK_GOOGLE', now(), now() + interval '5 minutes')
                    """, "b".repeat(64));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.google_auth_flows WHERE flow_type = 'LINK_CONFIRM'", Integer.class))
                    .isEqualTo(1);

            jdbc.update("""
                    INSERT INTO auth.security_mail_outbox(operation_id, purpose, user_id, reset_token_id, state, payload, created_at, expires_at)
                    VALUES (?, 'GOOGLE_LINKED', 1, NULL, 'PENDING', 'sealed', now(), now() + interval '1 day'),
                           (?, 'GOOGLE_UNLINKED', 1, NULL, 'PENDING', 'sealed', now(), now() + interval '1 day')
                    """, UUID.randomUUID().toString(), UUID.randomUUID().toString());
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM auth.security_mail_outbox WHERE purpose IN ('GOOGLE_LINKED', 'GOOGLE_UNLINKED')",
                    Integer.class)).isEqualTo(2);

            String invalidFlowHash = "c".repeat(64);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO auth.google_auth_flows(token_hash, flow_type, provider, created_at, expires_at)
                    VALUES (?, 'INVALID_FLOW', 'GOOGLE', now(), now() + interval '5 minutes')
                    """, invalidFlowHash))
                    .isInstanceOf(DataIntegrityViolationException.class);

            String invalidResetOpId = UUID.randomUUID().toString();
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO auth.security_mail_outbox(operation_id, purpose, user_id, reset_token_id, state, created_at, expires_at)
                    VALUES (?, 'PASSWORD_RESET', 1, NULL, 'PENDING', now(), now() + interval '1 day')
                    """, invalidResetOpId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            adminJdbc.execute("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
        }
    }
}
