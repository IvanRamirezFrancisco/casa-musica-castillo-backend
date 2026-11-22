import com.security.config.LocalDevelopmentConfiguration;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Explicit local migration procedure for security_db (127.0.0.1:5432, OID 33230).
 * Default plan mode is strictly read-only as spring_app and requests no admin password.
 */
public class LocalDatabaseMigrations {
    static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    static final List<String> EXPECTED = List.of("60", "61");
    static final String V60_FILE = "V60__google_federated_identity.sql";
    static final String V61_FILE = "V61__harden_refresh_tokens_rotation.sql";
    static final String APPROVAL_PHRASE = "APLICAR security_db V60-V61";

    static Properties load(Path path) throws Exception {
        var result = new Properties();
        try (var in = Files.newInputStream(path)) {
            result.load(in);
        }
        return result;
    }

    static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static long scalarCount(Connection conn, String sql) throws SQLException {
        try (var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void verifyAppliedChecksums(Connection connection) throws Exception {
        try (var st = connection.createStatement();
             var rs = st.executeQuery("SELECT version, script, checksum FROM public.flyway_schema_history WHERE success AND checksum IS NOT NULL ORDER BY installed_rank")) {
            int count = 0;
            while (rs.next()) {
                count++;
                Path script = ROOT.resolve("src/main/resources/db/migration").resolve(rs.getString(2)).normalize();
                if (!script.startsWith(ROOT.resolve("src/main/resources/db/migration")) || !Files.isRegularFile(script)) {
                    throw new IllegalStateException("Missing or invalid applied migration path: " + rs.getString(2));
                }
                var crc = new CRC32();
                for (String line : Files.readAllLines(script, StandardCharsets.UTF_8)) {
                    crc.update(line.replaceFirst("^\\uFEFF", "").getBytes(StandardCharsets.UTF_8));
                }
                if ((int) crc.getValue() != rs.getInt(3)) {
                    throw new IllegalStateException("Applied migration checksum mismatch on V" + rs.getString(1) + "; repair is not permitted");
                }
            }
            System.out.println("Applied Flyway checksums verified intact: " + count + " script(s).");
        }
    }

    static Map<String, Long> captureDataCounts(Connection conn) throws SQLException {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("auth.users", scalarCount(conn, "SELECT count(*) FROM auth.users"));
        counts.put("auth.refresh_tokens", scalarCount(conn, "SELECT count(*) FROM auth.refresh_tokens"));
        counts.put("catalog.products", scalarCount(conn, "SELECT count(*) FROM catalog.products"));
        counts.put("sales.orders", scalarCount(conn, "SELECT count(*) FROM sales.orders"));
        counts.put("auth.active_sessions", scalarCount(conn, "SELECT count(*) FROM auth.active_sessions"));
        counts.put("public.flyway_schema_history", scalarCount(conn, "SELECT count(*) FROM public.flyway_schema_history"));
        return counts;
    }

    static void preflightChecks(Connection conn) throws SQLException {
        var counts = captureDataCounts(conn);
        System.out.println("Historical data counts (unchanged by plan mode): " + counts);

        long nullPwd = scalarCount(conn, "SELECT count(*) FROM auth.users WHERE password IS NULL");
        long shortPwd = scalarCount(conn, "SELECT count(*) FROM auth.users WHERE password IS NOT NULL AND length(trim(password)) < 8");
        long nullRtExpiry = scalarCount(conn, "SELECT count(*) FROM auth.refresh_tokens WHERE expiry_date IS NULL");
        System.out.println("User password audit: password_null=" + nullPwd + ", password_lt_8=" + shortPwd + "; refresh_tokens_null_expiry=" + nullRtExpiry);
        if (shortPwd > 0) {
            throw new IllegalStateException("V60 constraint ck_users_password_nonblank would fail: found " + shortPwd + " user(s) with non-null password shorter than 8 chars");
        }
        if (nullRtExpiry > 0) {
            throw new IllegalStateException("V61 NOT NULL on absolute_expires_at would fail: found " + nullRtExpiry + " refresh_tokens with NULL expiry_date");
        }

        long tableCollisions = scalarCount(conn, """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'auth'
                  AND table_name IN ('external_identities', 'google_auth_flows', 'consumed_google_tokens')""");
        if (tableCollisions > 0) {
            throw new IllegalStateException("V60 conflict: one or more target tables already exist in schema auth");
        }

        long avatarCol = scalarCount(conn, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'auth' AND table_name = 'users' AND column_name = 'avatar_url'""");
        if (avatarCol > 0) {
            throw new IllegalStateException("V60 conflict: auth.users.avatar_url already exists");
        }

        long rtColCollisions = scalarCount(conn, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'auth' AND table_name = 'refresh_tokens'
                  AND column_name IN ('family_id','session_jti','absolute_expires_at','last_used_at','rotated_at',
                                      'revoked','revoked_at','revoke_reason','client_type','user_agent','ip_address')""");
        if (rtColCollisions > 0) {
            throw new IllegalStateException("V61 conflict: one or more target columns already exist on auth.refresh_tokens");
        }

        try (var st = conn.createStatement();
             var rs = st.executeQuery("""
                     SELECT con.conname, con.confdeltype::text
                     FROM pg_constraint con
                     JOIN pg_class rel ON rel.oid = con.conrelid
                     JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                     JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey)
                     WHERE nsp.nspname = 'auth' AND rel.relname = 'refresh_tokens'
                       AND con.contype = 'f' AND att.attname = 'user_id'""")) {
            var fks = new ArrayList<String>();
            while (rs.next()) {
                fks.add(rs.getString(1) + "(on_delete=" + rs.getString(2) + ")");
            }
            System.out.println("Existing FK on auth.refresh_tokens(user_id): " + fks + " -> V61 replaces with fk_refresh_tokens_user (ON DELETE CASCADE).");
        }

        try (var st = conn.createStatement();
             var rs = st.executeQuery("""
                     SELECT tablename, tableowner FROM pg_tables
                     WHERE schemaname = 'auth' AND tablename IN ('users', 'refresh_tokens')
                     ORDER BY tablename""")) {
            var owners = new ArrayList<String>();
            while (rs.next()) {
                owners.add("auth." + rs.getString(1) + "=" + rs.getString(2));
            }
            System.out.println("Table owners: " + owners + " (DDL requires postgres via -Apply).");
        }
    }

    static Flyway flyway(String url, String user, String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("filesystem:src/main/resources/db/migration")
                .schemas("public", "auth", "security", "catalog", "sales", "customer", "ops", "analytics")
                .defaultSchema("public")
                .baselineOnMigrate(false)
                .createSchemas(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .group(true)
                .target("61")
                .load();
    }

    static void leastPrivileges(Connection admin) throws Exception {
        admin.setAutoCommit(false);
        try (var st = admin.createStatement()) {
            String tables = "auth.external_identities, auth.google_auth_flows, auth.consumed_google_tokens";
            st.execute("REVOKE ALL PRIVILEGES ON TABLE " + tables + " FROM spring_app");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE " + tables + " TO spring_app");
            String seqs = "auth.external_identities_id_seq, auth.google_auth_flows_id_seq";
            st.execute("REVOKE ALL PRIVILEGES ON SEQUENCE " + seqs + " FROM spring_app");
            st.execute("GRANT USAGE ON SEQUENCE " + seqs + " TO spring_app");
            admin.commit();
        } catch (Exception ex) {
            admin.rollback();
            throw ex;
        }
    }

    static String toJsonSummary(String sha60, String sha61, String backupSha, Map<String, Long> before, Map<String, Long> after) {
        return "{\n"
                + "  \"database\": \"security_db\",\n"
                + "  \"port\": 5432,\n"
                + "  \"oid\": 33230,\n"
                + "  \"applied\": [\"60\", \"61\"],\n"
                + "  \"v60Sha256\": \"" + sha60 + "\",\n"
                + "  \"v61Sha256\": \"" + sha61 + "\",\n"
                + "  \"backupSha256\": \"" + backupSha + "\",\n"
                + "  \"applicationRole\": \"spring_app\",\n"
                + "  \"migrationRole\": \"postgres\",\n"
                + "  \"productionModified\": false,\n"
                + "  \"countsBefore\": {\"users\": " + before.get("auth.users") + ", \"refreshTokens\": " + before.get("auth.refresh_tokens")
                + ", \"products\": " + before.get("catalog.products") + ", \"orders\": " + before.get("sales.orders") + "},\n"
                + "  \"countsAfter\": {\"users\": " + after.get("auth.users") + ", \"refreshTokens\": " + after.get("auth.refresh_tokens")
                + ", \"products\": " + after.get("catalog.products") + ", \"orders\": " + after.get("sales.orders") + "}\n"
                + "}\n";
    }

    static void run(String mode) throws Exception {
        if (!List.of("plan", "apply").contains(mode)) {
            throw new IllegalArgumentException("Use plan or apply");
        }
        Path migDir = ROOT.resolve("src/main/resources/db/migration");
        Path p60 = migDir.resolve(V60_FILE);
        Path p61 = migDir.resolve(V61_FILE);
        if (!Files.isRegularFile(p60) || !Files.isRegularFile(p61)) {
            throw new IllegalStateException("Missing migration script V60 or V61 in src/main/resources/db/migration");
        }
        String sha60 = sha256(p60);
        String sha61 = sha256(p61);

        var properties = load(ROOT.resolve("src/main/resources/application-local.properties"));
        properties.putAll(load(ROOT.resolve(".local-dev/application.properties")));
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("reviewed-local-files", properties));
        LocalDevelopmentConfiguration.validate(env);
        if (!"security_db".equals(env.getRequiredProperty("app.local.database.expected-name"))
                || env.getRequiredProperty("app.local.database.expected-port", Integer.class) != 5432) {
            throw new IllegalStateException("This reviewed procedure is only for original security_db on 5432");
        }

        String url = env.getRequiredProperty("spring.datasource.url");
        String user = env.getRequiredProperty("spring.datasource.username");
        String password = env.getRequiredProperty("spring.datasource.password");

        Map<String, Long> beforeCounts;
        List<String> pending;
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setReadOnly(true);
            LocalDevelopmentConfiguration.verifyIdentity(connection, env, user);
            verifyAppliedChecksums(connection);
            beforeCounts = captureDataCounts(connection);
            preflightChecks(connection);
        }

        var appFlyway = flyway(url, user, password);
        pending = Arrays.stream(appFlyway.info().pending()).map(info -> info.getVersion().toString()).toList();
        System.out.println("Verified original database: security_db | 127.0.0.1 | 5432 | OID 33230 | application role spring_app");
        System.out.println(V60_FILE + " SHA-256: " + sha60);
        System.out.println(V61_FILE + " SHA-256: " + sha61);
        System.out.println("Pending reviewed migrations: " + pending);

        if (pending.isEmpty()) {
            appFlyway.validate();
            System.out.println("Schema already current (V61); no migrations executed.");
            return;
        }
        if (!pending.equals(EXPECTED)) {
            throw new IllegalStateException("Pending set differs from reviewed V60-V61: " + pending + "; new review required");
        }
        if ("plan".equals(mode)) {
            System.out.println("Dry-run (plan) completed cleanly in READ-ONLY mode. Zero DDL/DML was executed against security_db.");
            System.out.println("To apply after taking a fresh backup and reviewing impact, run with -Apply and confirm '" + APPROVAL_PHRASE + "'.");
            return;
        }

        if (!APPROVAL_PHRASE.equals(System.getenv("CMC_LOCAL_MIGRATION_APPROVAL"))) {
            throw new IllegalStateException("Explicit authorization '" + APPROVAL_PHRASE + "' is required");
        }
        String backupPathStr = System.getenv("CMC_LOCAL_MIGRATION_BACKUP_PATH");
        String backupShaExpected = System.getenv("CMC_LOCAL_MIGRATION_BACKUP_SHA256");
        if (backupPathStr == null || backupPathStr.isBlank() || backupShaExpected == null || backupShaExpected.isBlank()) {
            throw new IllegalStateException("A freshly verified pg_dump backup is required before applying V60-V61");
        }
        Path backupPath = Path.of(backupPathStr);
        if (!Files.isRegularFile(backupPath) || !sha256(backupPath).equalsIgnoreCase(backupShaExpected)) {
            throw new IllegalStateException("Backup file missing or SHA-256 mismatch; aborting migration");
        }

        String adminPassword = System.getenv("CMC_LOCAL_MIGRATION_PASSWORD");
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException("Administrative password must be entered privately in PowerShell");
        }

        try (var admin = DriverManager.getConnection(url, "postgres", adminPassword)) {
            admin.setReadOnly(true);
            LocalDevelopmentConfiguration.verifyIdentity(admin, env, "postgres");
            admin.setReadOnly(false);

            var migration = flyway(url, "postgres", adminPassword);
            int applied = migration.migrate().migrationsExecuted;
            if (applied != 2) {
                throw new IllegalStateException("Unexpected migration count executed: " + applied);
            }
            leastPrivileges(admin);
            migration.validate();
        }

        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setReadOnly(true);
            LocalDevelopmentConfiguration.verifyIdentity(connection, env, user);
            Map<String, Long> afterCounts = captureDataCounts(connection);
            if (!Objects.equals(beforeCounts.get("auth.users"), afterCounts.get("auth.users"))
                    || !Objects.equals(beforeCounts.get("auth.refresh_tokens"), afterCounts.get("auth.refresh_tokens"))
                    || !Objects.equals(beforeCounts.get("catalog.products"), afterCounts.get("catalog.products"))
                    || !Objects.equals(beforeCounts.get("sales.orders"), afterCounts.get("sales.orders"))) {
                throw new IllegalStateException("Post-migration historical count check detected unexpected row count delta");
            }
            scalarCount(connection, "SELECT count(*) FROM auth.external_identities");
            scalarCount(connection, "SELECT count(*) FROM auth.google_auth_flows");
            scalarCount(connection, "SELECT count(*) FROM auth.consumed_google_tokens");
            Files.writeString(
                    ROOT.resolve(".local-dev/original-migration-v60-v61-result.json"),
                    toJsonSummary(sha60, sha61, backupShaExpected, beforeCounts, afterCounts),
                    StandardCharsets.UTF_8);
        }
        System.out.println("V60 and V61 applied, least-privilege GRANTs applied for spring_app, and schema validated cleanly.");
    }

    public static void main(String[] args) {
        try {
            run(args.length == 0 ? "plan" : args[0]);
        } catch (Exception ex) {
            String detail = (ex instanceof SQLException sqle) ? " [SQLState=" + sqle.getSQLState() + "]" : "";
            System.err.println(ex instanceof IllegalStateException
                    ? ex.getMessage()
                    : "Local migration procedure stopped: " + ex.getClass().getSimpleName() + detail + ". No credentials are printed; review before retrying.");
            System.exit(1);
        }
    }
}
