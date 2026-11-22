[CmdletBinding()]
param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$pgBin = 'C:\Program Files\PostgreSQL\18\bin'
$pgDump = Join-Path $pgBin 'pg_dump.exe'
$pgRestore = Join-Path $pgBin 'pg_restore.exe'

Push-Location -LiteralPath $taskRoot
try {
    # Resolve/compile before requesting the administrative password. Maven opens no database.
    & mvn -B --no-transfer-progress compile dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=.local-dev/migration-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar la herramienta de migración.' }
    $taskDependencies = (Get-Content -LiteralPath .local-dev/migration-classpath.txt -Raw).Trim()
    $taskClasspath = (Join-Path $taskRoot 'target/classes') + [IO.Path]::PathSeparator + $taskDependencies

    & java --class-path $taskClasspath scripts/LocalDatabaseMigrations.java plan
    if ($LASTEXITCODE -ne 0) { throw 'El plan de solo lectura no pasó sus comprobaciones. No se ejecutará ninguna migración.' }
    if (-not $Apply) { return }

    $taskApproval = Read-Host 'Tras revisar el respaldo y el impacto, escribe APLICAR security_db V60-V61 para autorizar'
    if ($taskApproval -cne 'APLICAR security_db V60-V61') {
        throw 'Operación no autorizada; no se modificó la base.'
    }

    if (-not (Test-Path -LiteralPath $pgDump) -or -not (Test-Path -LiteralPath $pgRestore)) {
        throw 'No se encontraron pg_dump.exe o pg_restore.exe en PostgreSQL 18.'
    }

    $taskPassword = Read-Host 'Contraseña del rol postgres LOCAL (no se mostrará ni guardará)' -AsSecureString
    $taskPointer = [IntPtr]::Zero
    try {
        $taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskPassword)
        $plainPwd = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer)
        if ([string]::IsNullOrWhiteSpace($plainPwd)) {
            throw 'Contraseña vacía; operación cancelada sin modificar security_db.'
        }

        $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
        $backupDir = "C:\Users\ivanf\Documents\Respaldos privados Casa Musica\postgresql-before-v60-$ts"
        New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
        $dumpFile = Join-Path $backupDir 'security_db-before-v60.dump'

        $env:PGPASSWORD = $plainPwd
        & $pgDump -h 127.0.0.1 -p 5432 -U postgres -d security_db -Fc -f $dumpFile
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $dumpFile)) {
            throw 'Falló el respaldo previo pg_dump; no se aplicarán migraciones.'
        }
        & $pgRestore --list $dumpFile | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'No se pudo verificar el catálogo del archivo pg_dump; no se aplicarán migraciones.'
        }
        $dumpHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dumpFile).Hash.ToLowerInvariant()
        Write-Output ("Respaldo previo verificado: " + $dumpFile + " (SHA-256: " + $dumpHash + ")")

        $env:CMC_LOCAL_MIGRATION_PASSWORD = $plainPwd
        $env:CMC_LOCAL_MIGRATION_APPROVAL = $taskApproval
        $env:CMC_LOCAL_MIGRATION_BACKUP_PATH = $dumpFile
        $env:CMC_LOCAL_MIGRATION_BACKUP_SHA256 = $dumpHash

        & java --class-path $taskClasspath scripts/LocalDatabaseMigrations.java apply
        if ($LASTEXITCODE -ne 0) {
            throw 'La herramienta no confirmó el cierre. Revisa los logs antes de reintentar.'
        }
    } finally {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        Remove-Item Env:CMC_LOCAL_MIGRATION_PASSWORD -ErrorAction SilentlyContinue
        Remove-Item Env:CMC_LOCAL_MIGRATION_APPROVAL -ErrorAction SilentlyContinue
        Remove-Item Env:CMC_LOCAL_MIGRATION_BACKUP_PATH -ErrorAction SilentlyContinue
        Remove-Item Env:CMC_LOCAL_MIGRATION_BACKUP_SHA256 -ErrorAction SilentlyContinue
        $plainPwd = $null
        if ($taskPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer) }
        $taskPassword.Dispose()
    }
} finally {
    Pop-Location
}
