package com.security.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Validate the isolated local target before Flyway can write anything. */
@Configuration
@Profile("local")
public class LocalDevelopmentConfiguration {
    static void validate(Environment env) {
        if (!"jdbc:postgresql://127.0.0.1:55439/cmc_development".equals(env.getProperty("spring.datasource.url"))
                || !"cmc_dev".equals(env.getProperty("spring.datasource.username")))
            throw new IllegalStateException("Local requires the prepared isolated cmc_development database");
        if (!"smtp".equalsIgnoreCase(env.getProperty("EMAIL_PROVIDER", env.getProperty("app.email.provider", "")))
                || !"127.0.0.1".equals(env.getProperty("spring.mail.host"))
                || env.getProperty("spring.mail.port", Integer.class, 0) != 1025)
            throw new IllegalStateException("Local requires loopback Mailpit");
        for (String key : List.of("app.email.brevo.api-key", "app.email.resend.api-key", "app.email.mailgun.api-key",
                "spring.mail.username", "spring.mail.password", "supabase.service-key")) {
            if (!env.getProperty(key, "").isBlank())
                throw new IllegalStateException("Local cannot use external delivery/storage credentials");
        }
        if (env.getProperty("app.mercadopago.enabled", Boolean.class, true)
                || !"LOCAL".equalsIgnoreCase(env.getProperty("storage.provider", "")))
            throw new IllegalStateException("Local requires disabled payments and local storage");
    }

    @Bean
    FlywayMigrationStrategy isolatedLocalMigrations(Environment env) {
        return flyway -> {
            validate(env);
            // Verify the actual connected identity and preparation marker, not only the JDBC text.
            try (Connection connection = DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                    env.getRequiredProperty("spring.datasource.username"), env.getRequiredProperty("spring.datasource.password"));
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("select current_database(),current_user,host(inet_server_addr()),"
                         + "inet_server_port(),shobj_description(oid,'pg_database') from pg_database where datname=current_database()")) {
                if (!result.next() || !"cmc_development".equals(result.getString(1)) || !"cmc_dev".equals(result.getString(2))
                        || !"127.0.0.1".equals(result.getString(3)) || result.getInt(4) != 55439
                        || !"CMC isolated local development; no commercial data".equals(result.getString(5)))
                    throw new IllegalStateException("Local database identity/marker mismatch; migrations refused");
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException("Cannot verify isolated local database; migrations refused");
            }
            if (!flyway.getConfiguration().isValidateOnMigrate())
                throw new IllegalStateException("Local migrations require validation on migrate");
            flyway.migrate();
            flyway.validate();
        };
    }
}
