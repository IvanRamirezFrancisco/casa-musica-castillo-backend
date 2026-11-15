package com.security.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Higher priority than ambient production environment variables. Test databases only. */
public abstract class LocalPostgresIntegrationTest {
    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:55439/backend_hardening_test");
        properties.add("spring.datasource.username", () -> "auth_test");
        properties.add("spring.datasource.password", () -> "synthetic-test-only");
        properties.add("spring.flyway.locations", () -> "classpath:db/test-baseline,classpath:db/migration");
    }
}
