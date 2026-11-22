[CmdletBinding()]
param(
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$migrationsDir = Join-Path $root 'src\main\resources\db\migration'
$v60 = Join-Path $migrationsDir 'V60__google_federated_identity.sql'
$v61 = Join-Path $migrationsDir 'V61__harden_refresh_tokens_rotation.sql'
$pgBin = 'C:\Program Files\PostgreSQL\18\bin'
$pgDump = Join-Path $pgBin 'pg_dump.exe'
$psql = Join-Path $pgBin 'psql.exe'

if (-not (Test-Path $v60) -or -not (Test-Path $v61)) {
    throw 'Faltan archivos de migración V60 o V61.'
}

$hash60 = (Get-FileHash -Algorithm SHA256 -LiteralPath $v60).Hash.ToLowerInvariant()
$hash61 = (Get-FileHash -Algorithm SHA256 -LiteralPath $v61).Hash.ToLowerInvariant()

Write-Output '=== PLAN DE MIGRACIONES V60-V61 PARA security_db (127.0.0.1:5432) ==='
Write-Output ("V60__google_federated_identity.sql      SHA-256: " + $hash60)
Write-Output ("V61__harden_refresh_tokens_rotation.sql SHA-256: " + $hash61)
Write-Output ("Modo: " + $(if ($Apply) { 'APPLY (requiere respaldo y confirmación explícita)' } else { 'PLAN (solo lectura)' }))

if (-not $Apply) {
    Write-Output 'Modo plan finalizado sin modificar PostgreSQL. Usa -Apply solo con autorización expresa del propietario.'
    exit 0
}

$confirmation = Read-Host 'Escribe exactamente APLICAR security_db V60-V61 para continuar'
if ($confirmation -cne 'APLICAR security_db V60-V61') {
    throw 'Confirmación cancelada. No se realizaron cambios en security_db.'
}

$securePwd = Read-Host -AsSecureString 'Contraseña del rol administrativo postgres (127.0.0.1:5432)'
$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePwd)
try {
    $plainPwd = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if ([string]::IsNullOrWhiteSpace($plainPwd)) {
        throw 'Contraseña vacía; operación cancelada.'
    }
    $env:PGPASSWORD = $plainPwd

    $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupDir = "C:\Users\ivanf\Documents\Respaldos privados Casa Musica\postgresql-before-v60-$ts"
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $dumpFile = Join-Path $backupDir 'security_db-before-v60.dump'

    & $pgDump -h 127.0.0.1 -p 5432 -U postgres -d security_db -Fc -f $dumpFile
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $dumpFile)) {
        throw 'Falló el respaldo previo pg_dump; no se aplicarán migraciones.'
    }
    $dumpHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dumpFile).Hash.ToLowerInvariant()
    Write-Output ("Respaldo previo verificado: " + $dumpFile + " (SHA-256: " + $dumpHash + ")")

    $currentVersion = & $psql -h 127.0.0.1 -p 5432 -U postgres -d security_db -t -A -c "SELECT max(version::int) FROM public.flyway_schema_history WHERE success;"
    if ($LASTEXITCODE -ne 0) {
        throw 'No se pudo consultar flyway_schema_history.'
    }
    Write-Output ("Versión Flyway actual en security_db: V" + $currentVersion.Trim())
    if ([int]$currentVersion.Trim() -lt 59) {
        throw 'Aplica primero V53-V59 con scripts/Invoke-LocalMigrations.ps1 -Apply antes de aplicar V60-V61.'
    }

    Push-Location -LiteralPath $root
    try {
        & mvn -B --no-transfer-progress compile dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=.local-dev/migration-classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo resolver el classpath de Flyway.' }
        $deps = (Get-Content -LiteralPath '.local-dev/migration-classpath.txt' -Raw).Trim()
        $cp = (Join-Path $root 'target/classes') + [IO.Path]::PathSeparator + $deps

        $env:CMC_V60_APPROVAL = $confirmation
        $env:CMC_V60_PASSWORD = $plainPwd
        $javaScript = @"
import org.flywaydb.core.Flyway;
public class ApplyV60V61 {
    public static void main(String[] args) {
        if (!"APLICAR security_db V60-V61".equals(System.getenv("CMC_V60_APPROVAL"))) throw new IllegalStateException("No autorizado");
        String pwd = System.getenv("CMC_V60_PASSWORD");
        var flyway = Flyway.configure()
            .dataSource("jdbc:postgresql://127.0.0.1:5432/security_db", "postgres", pwd)
            .locations("filesystem:src/main/resources/db/migration")
            .schemas("public", "auth", "security", "catalog", "sales", "customer", "ops", "analytics")
            .defaultSchema("public")
            .baselineOnMigrate(false).createSchemas(false).cleanDisabled(true)
            .validateOnMigrate(true).group(true).target("61").load();
        int count = flyway.migrate().migrationsExecuted;
        flyway.validate();
        System.out.println("Migraciones ejecutadas y validadas: " + count);
    }
}
"@
        $tmpJava = Join-Path $root '.local-dev\ApplyV60V61.java'
        Set-Content -LiteralPath $tmpJava -Value $javaScript -Encoding UTF8
        try {
            & java --class-path $cp $tmpJava
            if ($LASTEXITCODE -ne 0) { throw 'Falló la ejecución de Flyway V60-V61.' }
        } finally {
            Remove-Item -LiteralPath $tmpJava -ErrorAction SilentlyContinue
            Remove-Item Env:CMC_V60_APPROVAL -ErrorAction SilentlyContinue
            Remove-Item Env:CMC_V60_PASSWORD -ErrorAction SilentlyContinue
        }

        $grantsSql = @"
REVOKE ALL PRIVILEGES ON TABLE auth.external_identities, auth.google_auth_flows, auth.consumed_google_tokens FROM spring_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE auth.external_identities, auth.google_auth_flows, auth.consumed_google_tokens TO spring_app;
REVOKE ALL PRIVILEGES ON SEQUENCE auth.external_identities_id_seq, auth.google_auth_flows_id_seq FROM spring_app;
GRANT USAGE ON SEQUENCE auth.external_identities_id_seq, auth.google_auth_flows_id_seq TO spring_app;
"@
        $grantsSql | & $psql -h 127.0.0.1 -p 5432 -U postgres -d security_db -v ON_ERROR_STOP=1
        if ($LASTEXITCODE -ne 0) { throw 'Falló la asignación de privilegios mínimos a spring_app.' }
    } finally {
        Pop-Location
    }

    Write-Output 'V60 y V61 aplicadas y validadas correctamente sobre security_db.'
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    if ($bstr -ne [IntPtr]::Zero) {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
    $plainPwd = $null
    $securePwd.Dispose()
}
