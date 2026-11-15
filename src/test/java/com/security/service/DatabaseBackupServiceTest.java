package com.security.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseBackupServiceTest {
    @Test
    void forwardsExplicitLibpqTlsConfigurationWithoutPuttingThePasswordInArguments() throws Exception {
        var command = List.of("pg_dump", "--no-password");
        var process = DatabaseBackupService.configureProcess(command, "pg_dump",
                "jdbc:postgresql://lab.invalid:5544/staging?sslmode=verify-full&sslrootcert=%2Ftmp%2Ftest-ca.pem",
                "synthetic-password");

        assertEquals(command, process.command());
        assertEquals("verify-full", process.environment().get("PGSSLMODE"));
        assertEquals("/tmp/test-ca.pem", process.environment().get("PGSSLROOTCERT"));
        assertEquals("synthetic-password", process.environment().get("PGPASSWORD"));
    }

    @Test
    void mapsJdbcTlsShortcutAndHonorsExplicitMode() throws Exception {
        var command = List.of("pg_dump");
        var shortcut = DatabaseBackupService.configureProcess(command, "pg_dump",
                "jdbc:postgresql://lab.invalid/staging?ssl=true", "fixture");
        var explicit = DatabaseBackupService.configureProcess(command, "pg_dump",
                "jdbc:postgresql://lab.invalid/staging?ssl=true&sslmode=verify-ca", "fixture");
        var driverDefault = DatabaseBackupService.configureProcess(command, "pg_dump",
                "jdbc:postgresql://lab.invalid/staging?ssl=false", "fixture");

        assertEquals("verify-full", shortcut.environment().get("PGSSLMODE"));
        assertEquals("verify-ca", explicit.environment().get("PGSSLMODE"));
        assertEquals(System.getenv("PGSSLMODE"), driverDefault.environment().get("PGSSLMODE"));
    }

    @Test
    void archiveInspectionDoesNotReceiveDatabaseCredentials() throws Exception {
        var process = DatabaseBackupService.configureProcess(List.of("pg_restore", "--list", "test.dump"),
                "pg_restore --list", "", "fixture");
        assertFalse(process.environment().containsKey("PGPASSWORD"));
        assertEquals(ProcessBuilder.Redirect.DISCARD, process.redirectOutput());
    }

    @Test
    void rejectsInvalidDumpConfigurationWithoutDisclosingItsValues() {
        var error = assertThrows(DatabaseBackupService.BackupException.class,
                () -> DatabaseBackupService.configureProcess(List.of("pg_dump"), "pg_dump",
                        "invalid protocol with synthetic-sensitive-value", "fixture"));
        assertFalse(error.toString().contains("synthetic-sensitive-value"));
    }

    @Test
    void validatesDestinationWithoutDisclosingJdbcQueryParameters() throws Exception {
        assertEquals(new DatabaseBackupService.DbCoordinates("lab.invalid", 5544, "staging"),
                DatabaseBackupService.parseJdbcUrl("jdbc:postgresql://lab.invalid:5544/staging?ssl=true"));
        var missingHost = assertThrows(DatabaseBackupService.BackupException.class,
                () -> DatabaseBackupService.parseJdbcUrl("jdbc:postgresql:///staging?ssl=true"));
        var invalidUri = assertThrows(DatabaseBackupService.BackupException.class,
                () -> DatabaseBackupService.parseJdbcUrl("jdbc:postgresql://bad host/staging?password=fixture"));
        assertNull(missingHost.getCause());
        assertFalse(invalidUri.toString().contains("fixture"));
    }
}
