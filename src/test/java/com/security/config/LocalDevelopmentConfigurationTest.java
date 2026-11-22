package com.security.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalDevelopmentConfigurationTest {
    private MockEnvironment safe() {
        return new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/security_db")
            .withProperty("spring.datasource.username", "spring_app")
            .withProperty("app.local.database.expected-name", "security_db").withProperty("app.local.database.expected-port", "5432")
            .withProperty("app.local.database.expected-user", "spring_app").withProperty("app.local.database.expected-oid", "33230")
            .withProperty("app.email.provider", "smtp")
            .withProperty("spring.mail.host", "127.0.0.1").withProperty("spring.mail.port", "1025")
            .withProperty("app.mercadopago.enabled", "false").withProperty("storage.provider", "LOCAL");
    }
    @Test void acceptsOriginalAuthorizedDatabaseWithoutAnIsolatedMarker() {
        assertDoesNotThrow(() -> LocalDevelopmentConfiguration.validate(safe()));
    }
    @Test void rejectsRemoteDatabaseWrongPortNameAndConnectionOverrides() {
        for (String url : new String[]{"jdbc:postgresql://db.example.test:5432/security_db",
                "jdbc:postgresql://127.0.0.1:55439/security_db", "jdbc:postgresql://127.0.0.1:5432/cmc_development",
                "jdbc:postgresql://127.0.0.1:5432/security_db?user=postgres"})
            assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.validate(safe().withProperty("spring.datasource.url", url)));
        assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.validate(safe().withProperty("spring.flyway.url", "jdbc:postgresql://remote.example.test:5432/security_db")));
    }
    @Test void acceptsAnotherExplicitlyPinnedLoopbackTargetWithoutHardcodingItsName() {
        assertDoesNotThrow(() -> LocalDevelopmentConfiguration.validate(safe()
            .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:15432/authorized_local")
            .withProperty("app.local.database.expected-port", "15432").withProperty("app.local.database.expected-name", "authorized_local")));
    }
    @Test void applicationRoleCannotBeReplacedByAdministrativeMigrationCredentials() {
        assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.validate(safe().withProperty("spring.flyway.user", "postgres")));
        assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.validate(safe().withProperty("spring.datasource.username", "postgres")));
    }
    @Test void rejectsExternalMailCredentialsAndMissingIdentity() {
        for (String key : new String[]{"app.email.provider", "app.email.brevo.api-key", "spring.mail.host", "supabase.service-key"})
            assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.validate(safe().withProperty(key, "external")));
        assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.validate(safe().withProperty("app.local.database.expected-oid", "0")));
    }
    @Test void legacyProviderLabelDoesNotOverrideEffectiveLocalSmtpWithNoExternalKeys() {
        assertDoesNotThrow(()->LocalDevelopmentConfiguration.validate(safe().withProperty("EMAIL_PROVIDER","brevo")));
        assertThrows(IllegalStateException.class,()->LocalDevelopmentConfiguration.validate(safe().withProperty("EMAIL_PROVIDER","brevo").withProperty("app.email.brevo.api-key","not-a-real-key")));
    }
    @Test void verifiesTheActualOidBeforeAllowingStartup() throws Exception {
        Connection connection=mock(Connection.class);Statement statement=mock(Statement.class);ResultSet result=mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);when(result.getString(1)).thenReturn("security_db");when(result.getString(2)).thenReturn("spring_app");
        when(result.getString(3)).thenReturn("127.0.0.1");when(result.getInt(4)).thenReturn(5432);when(result.getLong(5)).thenReturn(33230L);
        assertDoesNotThrow(() -> LocalDevelopmentConfiguration.verifyIdentity(connection,safe(),"spring_app"));
        when(result.getLong(5)).thenReturn(999L);
        assertThrows(IllegalStateException.class, () -> LocalDevelopmentConfiguration.verifyIdentity(connection,safe(),"spring_app"));
    }
    @Test void pendingMigrationsBlockStartupWithoutRunningDdlOrClean() {
        Flyway flyway=mock(Flyway.class);MigrationInfoService info=mock(MigrationInfoService.class);MigrationInfo migration=mock(MigrationInfo.class);
        when(flyway.info()).thenReturn(info);when(info.pending()).thenReturn(new MigrationInfo[]{migration});when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("53"));
        assertTrue(assertThrows(IllegalStateException.class,()->LocalDevelopmentConfiguration.requireNoPendingMigrations(flyway)).getMessage().contains("53"));
        verify(flyway,never()).migrate();verify(flyway,never()).clean();verify(flyway,never()).validate();
    }
    @Test void currentDatabaseIsValidatedWithoutApplyingAnyMigration() {
        Flyway flyway=mock(Flyway.class);MigrationInfoService info=mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);when(info.pending()).thenReturn(new MigrationInfo[0]);
        LocalDevelopmentConfiguration.requireNoPendingMigrations(flyway);
        verify(flyway).validate();verify(flyway,never()).migrate();verify(flyway,never()).clean();
    }
}
