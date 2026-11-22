package com.security.service;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class PasswordSchemaUpgradeTest {
    @Test void upgradesV58AndPreservesAnExistingRecoveryLinkByDigest() {
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55439/password_upgrade_test","auth_test","synthetic-test-only");
        var jdbc=new JdbcTemplate(ds);
        assertEquals("password_upgrade_test",jdbc.queryForObject("SELECT current_database()",String.class));
        var before=Flyway.configure().dataSource(ds).schemas("public","auth","security","catalog","sales","customer","ops","analytics")
            .initSql("SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics")
            .locations("classpath:db/test-baseline","classpath:db/migration").target("58").load();
        before.migrate();
        jdbc.update("""
            INSERT INTO auth.users(id,username,first_name,last_name,email,password,enabled,
            account_non_expired,account_non_locked,credentials_non_expired,two_factor_enabled,
            google_auth_enabled,email_enabled,backup_codes_enabled,is_customer,created_at)
            VALUES (1,'password_upgrade','Synthetic','Upgrade','upgrade@example.invalid','synthetic-hash',true,
            true,true,true,false,false,false,false,true,now())
            """);
        String raw="K".repeat(64);
        jdbc.update("INSERT INTO security.password_reset_tokens(token,user_id,expiry_date,used,created_at,attempts) VALUES (?,1,now()+interval '1 hour',false,now(),0)",raw);
        var expires=jdbc.queryForObject("SELECT expiry_date FROM security.password_reset_tokens",java.sql.Timestamp.class);
        var after=Flyway.configure().dataSource(ds).schemas("public","auth","security","catalog","sales","customer","ops","analytics")
            .initSql("SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics")
            .locations("classpath:db/test-baseline","classpath:db/migration").target("59").load();
        assertEquals(1,after.migrate().migrationsExecuted);after.validate();
        assertEquals(SecurePasswordResetService.resetDigest(raw),jdbc.queryForObject("SELECT token FROM security.password_reset_tokens",String.class));
        assertEquals(expires,jdbc.queryForObject("SELECT expiry_date FROM security.password_reset_tokens",java.sql.Timestamp.class));
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT delivery_state FROM security.password_reset_tokens",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM auth.security_mail_outbox",Integer.class));
    }
}
