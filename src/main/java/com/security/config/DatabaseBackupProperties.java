package com.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de configuración para el sistema de respaldo de base de datos.
 *
 * <p>Se leen del bloque {@code app.backup} en {@code application.yml} /
 * {@code application-local.yml}.
 *
 * <pre>
 * app:
 *   backup:
 *     pg-dump-path: "C:/Program Files/PostgreSQL/18/bin/pg_dump.exe"
 *     temp-dir:     "C:/casamusica/backup-temp"   # borrado tras subir a Supabase
 * </pre>
 */
@ConfigurationProperties(prefix = "app.backup")
public class DatabaseBackupProperties {

    /**
     * Ruta absoluta al ejecutable {@code pg_dump}.
     * <ul>
     *   <li>Windows: {@code C:/Program Files/PostgreSQL/18/bin/pg_dump.exe}</li>
     *   <li>Linux/Railway: {@code pg_dump} (si está en el PATH del sistema)</li>
     * </ul>
     */
    private String pgDumpPath = "pg_dump";
    private String pgRestorePath = "pg_restore";

    /**
     * Directorio <em>temporal</em> donde se escribe el volcado mientras se
     * procesa. El archivo se elimina automáticamente tras subirlo a Supabase Storage.
     *
     * <p>En Windows desarrollo: {@code C:/casamusica/backup-temp}
     * <br>En Linux/Railway:      {@code /tmp/casamusica-backups}
     */
    private String tempDir = System.getProperty("java.io.tmpdir") + "/casamusica-backups-tmp";

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public String getPgDumpPath()           { return pgDumpPath; }
    public void setPgDumpPath(String v)     { this.pgDumpPath = v; }
    public String getPgRestorePath()        { return pgRestorePath; }
    public void setPgRestorePath(String v)  { this.pgRestorePath = v; }

    public String getTempDir()              { return tempDir; }
    public void setTempDir(String v)        { this.tempDir = v; }
}
