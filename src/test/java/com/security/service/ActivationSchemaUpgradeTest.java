package com.security.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class ActivationSchemaUpgradeTest {
    @Test void additiveUpgradePreservesLegacyLinksAndBoundsOldCodes() {
        var db = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55439/activation_upgrade_test",
            "auth_test","synthetic-test-only");
        var jdbc = new JdbcTemplate(db);
        assertEquals("activation_upgrade_test",jdbc.queryForObject("SELECT current_database()",String.class));
        var baseline = Flyway.configure().dataSource(db)
            .schemas("public","auth","security","catalog","sales","customer","ops","analytics")
            .initSql("SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics")
            .locations("classpath:db/test-baseline","classpath:db/migration").target("57").load();
        baseline.migrate();
        jdbc.update("""
            INSERT INTO auth.users(id,username,first_name,last_name,email,password,enabled,
              account_non_expired,account_non_locked,credentials_non_expired,two_factor_enabled,
              google_auth_enabled,email_enabled,backup_codes_enabled,is_customer,created_at,pending_self_registration)
            VALUES (1,'activation_upgrade','Synthetic','Upgrade','upgrade@example.invalid','synthetic-hash',false,
              true,true,true,false,false,false,false,true,now(),true)
            """);
        jdbc.update("""
            INSERT INTO auth.verification_tokens(token,user_id,expiry_date,token_type,used,created_at,
                code_digest,code_attempts,recipient_email)
            VALUES (?,1,now()+interval '23 hours','EMAIL_VERIFICATION',false,now()-interval '1 hour',?,0,'upgrade@example.invalid')
            ""","Z".repeat(32),"a".repeat(64));
        var original = jdbc.queryForMap("SELECT token,expiry_date,recipient_email FROM auth.verification_tokens");
        var upgrade = Flyway.configure().dataSource(db)
            .schemas("public","auth","security","catalog","sales","customer","ops","analytics")
            .initSql("SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics")
            .locations("classpath:db/test-baseline","classpath:db/migration").target("58").load();
        assertEquals(1,upgrade.migrate().migrationsExecuted);upgrade.validate();
        assertEquals(original,jdbc.queryForMap("SELECT token,expiry_date,recipient_email FROM auth.verification_tokens"));
        assertEquals(Boolean.TRUE,jdbc.queryForObject("SELECT code_expires_at <= now() FROM auth.verification_tokens",Boolean.class));
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT delivery_state FROM auth.verification_tokens",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM auth.activation_request_limits",Integer.class));
        // An older server can still insert a link without supplying the additive columns.
        jdbc.update("""
            INSERT INTO auth.verification_tokens(token,user_id,expiry_date,token_type,used,created_at,recipient_email)
            VALUES (?,1,now()+interval '24 hours','EMAIL_VERIFICATION',false,now(),'upgrade@example.invalid')
            ""","Y".repeat(32));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM auth.verification_tokens WHERE delivery_state='ACCEPTED'",Integer.class));
    }
}
