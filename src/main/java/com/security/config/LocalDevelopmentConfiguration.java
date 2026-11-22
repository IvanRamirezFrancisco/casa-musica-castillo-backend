package com.security.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Pin the explicitly authorized local database. Application startup never applies pending DDL. */
@Configuration
@Profile("local")
public class LocalDevelopmentConfiguration {
    public static void validate(Environment env) {
        validateUrl(env.getRequiredProperty("spring.datasource.url"), env);
        String expectedUser = env.getRequiredProperty("app.local.database.expected-user");
        if (!expectedUser.equals(env.getProperty("spring.datasource.username"))
                || !expectedUser.equals(env.getProperty("spring.flyway.user", expectedUser)))
            throw new IllegalStateException("Local application must use its authorized application role; migrate separately");
        String flywayUrl = env.getProperty("spring.flyway.url");
        if (flywayUrl != null) validateUrl(flywayUrl, env);
        if (env.getProperty("app.local.database.expected-oid", Long.class, 0L) <= 0)
            throw new IllegalStateException("Local requires an explicitly verified database identity");
        if (!"smtp".equalsIgnoreCase(env.getProperty("app.email.provider", ""))
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

    private static void validateUrl(String jdbcUrl, Environment env) {
        try {
            if (!jdbcUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            URI uri = URI.create(jdbcUrl.substring(5));
            if (!("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()))
                    || uri.getPort() != env.getRequiredProperty("app.local.database.expected-port", Integer.class)
                    || !uri.getPath().equals("/" + env.getRequiredProperty("app.local.database.expected-name"))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Local connection does not match the authorized loopback database");
        }
    }

    public static void verifyIdentity(Connection connection, Environment env, String expectedUser) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("select current_database(),current_user,host(inet_server_addr()),"
                     + "inet_server_port(),oid::bigint from pg_database where datname=current_database()")) {
            if (!result.next() || !env.getRequiredProperty("app.local.database.expected-name").equals(result.getString(1))
                    || !expectedUser.equals(result.getString(2)) || !"127.0.0.1".equals(result.getString(3))
                    || result.getInt(4) != env.getRequiredProperty("app.local.database.expected-port", Integer.class)
                    || result.getLong(5) != env.getRequiredProperty("app.local.database.expected-oid", Long.class))
                throw new IllegalStateException("Local database identity mismatch; no migration permitted");
        }
    }

    static void requireNoPendingMigrations(org.flywaydb.core.Flyway flyway) {
        var pending = flyway.info().pending();
        if (pending.length != 0) {
            String versions = java.util.Arrays.stream(pending).map(info -> info.getVersion().toString())
                .collect(java.util.stream.Collectors.joining(", "));
            throw new IllegalStateException("Local database has pending migrations: " + versions
                + ". Review and authorize scripts/Invoke-LocalMigrations.ps1; application startup does not run DDL");
        }
        flyway.validate();
    }

    @Bean
    FlywayMigrationStrategy authorizedLocalSchema(Environment env) {
        return flyway -> {
            validate(env);
            // Verify the actual database OID and server, without writing a marker into user data.
            try (Connection connection = DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                    env.getRequiredProperty("spring.datasource.username"), env.getRequiredProperty("spring.datasource.password"))) {
                connection.setReadOnly(true);
                verifyIdentity(connection, env, env.getRequiredProperty("app.local.database.expected-user"));
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException("Cannot verify authorized local database; startup refused");
            }
            requireNoPendingMigrations(flyway);
        };
    }
}
