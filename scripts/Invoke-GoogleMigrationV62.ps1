[CmdletBinding()]
param(
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$migrationsDir = Join-Path $taskRoot 'src\main\resources\db\migration'
$v62File = Join-Path $migrationsDir 'V62__google_link_confirmation_and_security_mail.sql'
$localPropsFile = Join-Path $taskRoot '.local-dev\application.properties'
$pgBin = 'C:\Program Files\PostgreSQL\18\bin'
$psql = Join-Path $pgBin 'psql.exe'
$pgDump = Join-Path $pgBin 'pg_dump.exe'
$pgRestore = Join-Path $pgBin 'pg_restore.exe'

if (-not (Test-Path -LiteralPath $v62File)) {
    throw "No se encontró el script de migración V62 en: $v62File"
}
if (-not (Test-Path -LiteralPath $localPropsFile)) {
    throw "No se encontró la configuración privada local en: $localPropsFile"
}
if (-not (Test-Path -LiteralPath $psql)) {
    throw "No se encontró psql.exe en: $psql"
}

function Read-LocalProperties {
    param([string]$Path)
    $map = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -le 0) { continue }
        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1).Trim()
        $map[$key] = $val
    }
    return $map
}

function Resolve-ToolPath {
    param([string]$CommandName, [string]$FallbackPath)
    $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source) { return $cmd.Source }
    if (Test-Path -LiteralPath $FallbackPath) { return $FallbackPath }
    throw "No se encontró el ejecutable requerido '$CommandName' ni en '$FallbackPath'."
}

function Invoke-PsqlScalar {
    param(
        [string]$User,
        [string]$Password,
        [string]$Sql
    )
    $prev = $env:PGPASSWORD
    $env:PGPASSWORD = $Password
    try {
        $out = & $psql -X -w -At -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 5432 -U $User -d security_db -c $Sql 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Error al consultar security_db con psql: $($out -join [Environment]::NewLine)"
        }
        return ($out | Out-String).Trim()
    } finally {
        if ($null -eq $prev) {
            Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        } else {
            $env:PGPASSWORD = $prev
        }
    }
}

function Get-SecurityDbBaselineCounts {
    param([string]$User, [string]$Password)
    $sql = @"
SELECT 'auth.users=' || count(*) FROM auth.users
UNION ALL SELECT 'auth.external_identities=' || count(*) FROM auth.external_identities
UNION ALL SELECT 'auth.active_sessions=' || count(*) FROM auth.active_sessions
UNION ALL SELECT 'auth.backup_codes=' || count(*) FROM auth.backup_codes
UNION ALL SELECT 'auth.google_auth_flows=' || count(*) FROM auth.google_auth_flows
UNION ALL SELECT 'auth.security_mail_outbox=' || count(*) FROM auth.security_mail_outbox
UNION ALL SELECT 'catalog.products=' || count(*) FROM catalog.products
UNION ALL SELECT 'sales.orders=' || count(*) FROM sales.orders
UNION ALL SELECT 'sales.shopping_carts=' || count(*) FROM sales.shopping_carts
UNION ALL SELECT 'sales.cart_items=' || count(*) FROM sales.cart_items
UNION ALL SELECT 'sales.wishlists=' || count(*) FROM sales.wishlists;
"@
    $raw = Invoke-PsqlScalar -User $User -Password $Password -Sql $sql
    $counts = [ordered]@{}
    foreach ($line in ($raw -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -match '^([^=]+)=(\d+)$') {
            $counts[$matches[1]] = [long]$matches[2]
        }
    }
    return $counts
}

$props = Read-LocalProperties -Path $localPropsFile
$appUrl = $props['spring.datasource.url']
$appUser = $props['spring.datasource.username']
$appPass = $props['spring.datasource.password']
$expectedDb = if ($props.ContainsKey('app.local.database.expected-name')) { $props['app.local.database.expected-name'] } else { 'security_db' }
$expectedOid = if ($props.ContainsKey('app.local.database.expected-oid')) { [long]$props['app.local.database.expected-oid'] } else { 33230 }
$expectedPort = if ($props.ContainsKey('app.local.database.expected-port')) { [int]$props['app.local.database.expected-port'] } else { 5432 }

if ($appUrl -ne 'jdbc:postgresql://127.0.0.1:5432/security_db' -or $appUser -ne 'spring_app' -or $expectedDb -ne 'security_db' -or $expectedOid -ne 33230 -or $expectedPort -ne 5432) {
    throw 'La configuración local no coincide con security_db (127.0.0.1:5432, OID 33230, rol spring_app).'
}

$v62Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v62File).Hash.ToLowerInvariant()
Write-Output '=== PREFLIGHT MIGRACION V62 SOBRE PostgreSQL LOCAL ORIGINAL ==='
Write-Output ("Archivo V62: $v62File")
Write-Output ("SHA-256 V62: $v62Hash")
Write-Output ("Modo: " + $(if ($Apply) { 'APPLY (requiere respaldo verificable y confirmación explícita)' } else { 'PLAN (solo lectura)' }))

$identityRaw = Invoke-PsqlScalar -User $appUser -Password $appPass -Sql "SELECT current_database() || '|' || current_user || '|' || host(inet_server_addr()) || '|' || inet_server_port() || '|' || (SELECT oid::bigint FROM pg_database WHERE datname = current_database());"
if ($identityRaw -ne 'security_db|spring_app|127.0.0.1|5432|33230') {
    throw "Identidad inesperada en PostgreSQL local: '$identityRaw' (se esperaba 'security_db|spring_app|127.0.0.1|5432|33230')."
}
Write-Output "Identidad verificada: $identityRaw"

$failedCount = [int](Invoke-PsqlScalar -User $appUser -Password $appPass -Sql "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success;")
if ($failedCount -ne 0) {
    throw "Se detectaron $failedCount migración(es) fallida(s) en public.flyway_schema_history. Operación bloqueada."
}

$maxVersionRaw = Invoke-PsqlScalar -User $appUser -Password $appPass -Sql "SELECT coalesce(max(version::int), 0) FROM public.flyway_schema_history WHERE success AND version ~ '^\d+$';"
$maxVersion = [int]$maxVersionRaw
Write-Output ("Versión Flyway máxima aplicada con éxito: V$maxVersion (fallidas: $failedCount)")

$constraintsRaw = Invoke-PsqlScalar -User $appUser -Password $appPass -Sql @"
SELECT t.relname || '.' || c.conname || ' => ' || pg_get_constraintdef(c.oid)
FROM pg_constraint c
JOIN pg_class t ON t.oid = c.conrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = 'auth' AND t.relname IN ('google_auth_flows', 'security_mail_outbox') AND c.contype = 'c'
ORDER BY t.relname, c.conname;
"@
Write-Output 'Constraints CHECK actuales en tablas objetivo de V62:'
Write-Output $constraintsRaw

$conflictsFlows = [int](Invoke-PsqlScalar -User $appUser -Password $appPass -Sql "SELECT count(*) FROM auth.google_auth_flows WHERE flow_type NOT IN ('NONCE', 'REGISTRATION', 'REAUTH', 'LINK_CONFIRM');")
$conflictsOutbox = [int](Invoke-PsqlScalar -User $appUser -Password $appPass -Sql "SELECT count(*) FROM auth.security_mail_outbox WHERE NOT ((purpose = 'PASSWORD_RESET' AND reset_token_id IS NOT NULL) OR (purpose IN ('PASSWORD_CHANGED', 'GOOGLE_LINKED', 'GOOGLE_UNLINKED') AND reset_token_id IS NULL));")
if ($conflictsFlows -ne 0 -or $conflictsOutbox -ne 0) {
    throw "Se encontraron filas incompatibles con V62: google_auth_flows=$conflictsFlows, security_mail_outbox=$conflictsOutbox."
}
Write-Output 'Comprobación de compatibilidad de datos existentes con V62: 0 conflictos.'

$countsBefore = Get-SecurityDbBaselineCounts -User $appUser -Password $appPass
Write-Output 'Conteos originales verificados:'
foreach ($entry in $countsBefore.GetEnumerator()) {
    Write-Output ("  - $($entry.Key): $($entry.Value)")
}

if ($maxVersion -ge 62) {
    Write-Output 'V62 ya se encuentra aplicada en security_db. No se requieren cambios DDL.'
    exit 0
}
if ($maxVersion -ne 61) {
    throw "Se esperaba Flyway V61 antes de aplicar V62, pero la versión actual es V$maxVersion."
}

if (-not $Apply) {
    Write-Output 'Modo PLAN finalizado sin modificar security_db. Para aplicar con respaldo previo, ejecuta con -Apply.'
    exit 0
}

$confirmation = Read-Host 'Tras revisar el plan y alcance, escribe exactamente APLICAR security_db V62 para autorizar'
if ($confirmation -cne 'APLICAR security_db V62') {
    throw 'Confirmación cancelada. No se modificó security_db.'
}

if (-not (Test-Path -LiteralPath $pgDump) -or -not (Test-Path -LiteralPath $pgRestore)) {
    throw "No se encontraron pg_dump.exe o pg_restore.exe en $pgBin."
}

$mavenExe = Resolve-ToolPath -CommandName 'mvn' -FallbackPath 'C:\Program Files\apache-maven-3.9.11\bin\mvn.cmd'
$javaExe = Resolve-ToolPath -CommandName 'java' -FallbackPath 'C:\Program Files\Java\jdk-21\bin\java.exe'

$securePwd = Read-Host -AsSecureString 'Contraseña del rol administrativo postgres LOCAL (127.0.0.1:5432)'
$bstr = [IntPtr]::Zero
$plainPwd = $null
$tmpJava = Join-Path $taskRoot '.local-dev\ApplyV62.java'

try {
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePwd)
    $plainPwd = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if ([string]::IsNullOrWhiteSpace($plainPwd)) {
        throw 'Contraseña vacía; operación cancelada sin modificar security_db.'
    }

    $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupDir = "C:\Users\ivanf\Documents\Respaldos privados Casa Musica\postgresql-before-v62-$ts"
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $dumpFile = Join-Path $backupDir 'security_db-before-v62.dump'

    $env:PGPASSWORD = $plainPwd
    & $pgDump -h 127.0.0.1 -p 5432 -U postgres -d security_db -Fc -f $dumpFile
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $dumpFile)) {
        throw 'Falló el respaldo preventivo con pg_dump.exe; no se aplicará V62.'
    }

    $toc = & $pgRestore --list $dumpFile
    if ($LASTEXITCODE -ne 0) {
        throw 'Falló la verificación del respaldo con pg_restore --list; no se aplicará V62.'
    }
    $tocEntries = ($toc | Where-Object { $_ -and -not $_.StartsWith(';') }).Count
    $dumpBytes = (Get-Item -LiteralPath $dumpFile).Length
    $dumpHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dumpFile).Hash.ToLowerInvariant()
    Write-Output "Respaldo preventivo verificado:"
    Write-Output ("  Archivo:  $dumpFile")
    Write-Output ("  Tamaño:   $dumpBytes bytes ($tocEntries entradas TOC)")
    Write-Output ("  SHA-256:  $dumpHash")

    Push-Location -LiteralPath $taskRoot
    try {
        & $mavenExe -B --no-transfer-progress compile dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=.local-dev/migration-classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar ni resolver el classpath de Flyway.' }
        $deps = (Get-Content -LiteralPath '.local-dev/migration-classpath.txt' -Raw).Trim()
        $cp = (Join-Path $taskRoot 'target/classes') + [IO.Path]::PathSeparator + $deps

        $env:CMC_V62_APPROVAL = $confirmation
        $env:CMC_V62_PASSWORD = $plainPwd
        $javaSource = @'
import org.flywaydb.core.Flyway;

public class ApplyV62 {
    public static void main(String[] args) {
        if (!"APLICAR security_db V62".equals(System.getenv("CMC_V62_APPROVAL"))) {
            throw new IllegalStateException("No autorizado");
        }
        String pwd = System.getenv("CMC_V62_PASSWORD");
        var flyway = Flyway.configure()
                .dataSource("jdbc:postgresql://127.0.0.1:5432/security_db", "postgres", pwd)
                .locations("filesystem:src/main/resources/db/migration")
                .schemas("public", "auth", "security", "catalog", "sales", "customer", "ops", "analytics")
                .defaultSchema("public")
                .baselineOnMigrate(false)
                .createSchemas(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .group(true)
                .target("62")
                .load();
        var pending = flyway.info().pending();
        if (pending.length != 1 || pending[0].getVersion() == null || !"62".equals(pending[0].getVersion().toString())) {
            throw new IllegalStateException("Solo se autoriza aplicar exclusivamente V62; pendientes=" + pending.length);
        }
        int executed = flyway.migrate().migrationsExecuted;
        flyway.validate();
        System.out.println("Flyway V62 ejecutadas=" + executed + " y validadas correctamente.");
    }
}
'@
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($tmpJava, $javaSource, $utf8NoBom)
        $firstBytes = [System.IO.File]::ReadAllBytes($tmpJava)
        if ($firstBytes.Length -ge 3 -and $firstBytes[0] -eq 0xEF -and $firstBytes[1] -eq 0xBB -and $firstBytes[2] -eq 0xBF) {
            throw 'Error interno: ApplyV62.java contiene BOM UTF-8.'
        }
        & $javaExe --class-path $cp $tmpJava
        if ($LASTEXITCODE -ne 0) { throw 'Falló la aplicación de Flyway V62.' }
    } finally {
        Pop-Location
    }

    $failedAfter = [int](Invoke-PsqlScalar -User $appUser -Password $appPass -Sql "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success;")
    $v62Row = Invoke-PsqlScalar -User $appUser -Password $appPass -Sql "SELECT version || '|' || description || '|' || success FROM public.flyway_schema_history WHERE version = '62';"
    $privCheck = Invoke-PsqlScalar -User $appUser -Password $appPass -Sql "SELECT has_table_privilege('spring_app', 'auth.google_auth_flows', 'SELECT,INSERT,UPDATE') || '|' || has_table_privilege('spring_app', 'auth.security_mail_outbox', 'SELECT,INSERT,UPDATE');"
    $countsAfter = Get-SecurityDbBaselineCounts -User $appUser -Password $appPass

    foreach ($key in $countsBefore.Keys) {
        if ($countsAfter[$key] -ne $countsBefore[$key]) {
            throw "Diferencia inesperada en conteo tras V62 para ${key}: antes=$($countsBefore[$key]), despues=$($countsAfter[$key])."
        }
    }

    Write-Output "Post-verificación V62 superada:"
    Write-Output ("  Registro Flyway: $v62Row (fallidas=$failedAfter)")
    Write-Output ("  Privilegios spring_app (flows|outbox): $privCheck")
    Write-Output '  Todos los conteos de usuarios, identidades Google, sesiones, códigos, catálogo, carritos, favoritos y pedidos se conservaron idénticos.'
} finally {
    Remove-Item -LiteralPath $tmpJava -ErrorAction SilentlyContinue
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CMC_V62_APPROVAL -ErrorAction SilentlyContinue
    Remove-Item Env:CMC_V62_PASSWORD -ErrorAction SilentlyContinue
    $plainPwd = $null
    if ($bstr -ne [IntPtr]::Zero) { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if ($securePwd) { $securePwd.Dispose() }
}
