package com.security.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Schema50UpgradeTest {
    // Fixed disposable database. The production datasource is never consulted.
    private final DriverManagerDataSource database = new DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:55439/schema50_upgrade_test", "auth_test", "synthetic-test-only");

    @Test
    void upgradesSyntheticVersion50Through52AndPreservesExistingRows() {
        var jdbc = new JdbcTemplate(database);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("schema50_upgrade_test");

        var schema50 = flyway("50");
        assertThat(schema50.info().current()).isNull();
        schema50.migrate();
        assertThat(schema50.info().current().getVersion().getVersion()).isEqualTo("50");
        assertThat(Arrays.stream(schema50.info().applied()).filter(migration -> migration.getVersion() != null).count())
                .isEqualTo(48);
        var originalHistory = jdbc.queryForList(
                "SELECT installed_rank,version,checksum,success FROM public.flyway_schema_history ORDER BY installed_rank");

        jdbc.update("""
                INSERT INTO auth.users(id,username,first_name,last_name,email,password,enabled,
                  account_non_expired,account_non_locked,credentials_non_expired,two_factor_enabled,
                  google_auth_enabled,google_auth_secret,email_enabled,backup_codes_enabled,is_customer,created_at)
                VALUES (1,'synthetic','Synthetic','Upgrade','upgrade@example.invalid','synthetic-hash',true,
                  true,true,true,true,true,?,true,false,true,now())
                """, new com.warrenstrange.googleauth.GoogleAuthenticator().createCredentials().getKey());
        var originalAccount = jdbc.queryForMap("""
                SELECT email,password,enabled,google_auth_enabled,google_auth_secret,email_enabled
                FROM auth.users WHERE id = 1
                """);

        var schema51 = flyway("51");
        assertThat(schema51.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(schema51.info().current().getVersion().getVersion()).isEqualTo("51");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.login_challenges", Long.class)).isZero();
        jdbc.update("INSERT INTO auth.login_factor_limits(user_id, window_start, attempts) VALUES (1, now(), 2)");

        var schema52 = flyway("52");
        assertThat(schema52.migrate().migrationsExecuted).isEqualTo(1);
        schema52.validate();
        assertThat(schema52.info().current().getVersion().getVersion()).isEqualTo("52");
        var migratedAccount = jdbc.queryForMap("""
                SELECT email,password,enabled,google_auth_enabled,google_auth_secret,email_enabled
                FROM auth.users WHERE id = 1
                """);
        assertThat(migratedAccount.equals(originalAccount)).as("V50 account and TOTP enrollment preserved").isTrue();
        assertThat(jdbc.queryForList(
                "SELECT installed_rank,version,checksum,success FROM public.flyway_schema_history ORDER BY installed_rank LIMIT ?",
                originalHistory.size())).isEqualTo(originalHistory);
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth.login_factor_limits WHERE user_id = 1", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT last_verified_totp_slot FROM auth.login_factor_limits WHERE user_id = 1", Long.class)).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE auth.login_factor_limits SET last_verified_totp_slot = -1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(database)
                .schemas("public", "auth", "security", "catalog", "sales", "customer", "ops", "analytics")
                .locations("classpath:db/test-baseline", "classpath:db/migration")
                .initSql("SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics")
                .target(target).load();
    }
}
