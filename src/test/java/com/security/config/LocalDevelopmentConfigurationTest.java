package com.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class LocalDevelopmentConfigurationTest {
    private MockEnvironment safe() {
        return new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55439/cmc_development")
            .withProperty("spring.datasource.username", "cmc_dev").withProperty("app.email.provider", "smtp")
            .withProperty("spring.mail.host", "127.0.0.1").withProperty("spring.mail.port", "1025")
            .withProperty("app.mercadopago.enabled", "false").withProperty("storage.provider", "LOCAL");
    }
    @Test void acceptsOnlyPreparedLocalConfiguration() { assertDoesNotThrow(() -> LocalDevelopmentConfiguration.validate(safe())); }
    @Test void rejectsAmbientCommercialDatabaseBeforeMigrations() {
        assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.validate(safe()
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test:5432/commercial")));
    }
    @Test void rejectsExternalMailAndCredentialsBeforeMigrations() {
        for (String key : new String[]{"EMAIL_PROVIDER", "app.email.brevo.api-key", "spring.mail.host", "supabase.service-key"})
            assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.validate(safe().withProperty(key, "external")));
    }
}
