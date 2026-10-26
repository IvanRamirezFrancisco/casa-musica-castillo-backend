param([switch]$Stop)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$clusterPath = Join-Path $taskRoot 'target/auth-hardening-pg'
$serverLog = Join-Path $taskRoot 'target/auth-test-postgres.log'
$pgBin = Split-Path (Get-Command pg_ctl.exe -ErrorAction Stop).Source

if ($Stop) {
    & (Join-Path $pgBin 'pg_ctl.exe') -D $clusterPath stop -m fast
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo detener el clúster temporal de tests.' }
    exit
}
if (!(Test-Path -LiteralPath (Join-Path $clusterPath 'PG_VERSION'))) {
    & (Join-Path $pgBin 'initdb.exe') -D $clusterPath -U auth_test -A trust --no-locale -E UTF8
    if ($LASTEXITCODE -ne 0) { throw 'Falló initdb de tests.' }
}
& (Join-Path $pgBin 'pg_ctl.exe') -D $clusterPath status | Out-Null
if ($LASTEXITCODE -ne 0) {
    & (Join-Path $pgBin 'pg_ctl.exe') -D $clusterPath -l $serverLog -o '-h 127.0.0.1 -p 55439' start
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar el clúster de tests; no se usará otra instancia.' }
}
# Explicit loopback/port/user. Never reuse production PGHOST/PGUSER or DATABASE_URL.
foreach ($testDatabase in @('backend_hardening_test', 'auth_challenges_test')) {
    $exists = & (Join-Path $pgBin 'psql.exe') -h 127.0.0.1 -p 55439 -U auth_test -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$testDatabase'"
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo inspeccionar el clúster temporal.' }
    if ($exists -ne '1') {
        & (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p 55439 -U auth_test $testDatabase
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la base sintética de tests.' }
    }
}
Write-Output 'PostgreSQL de tests listo en 127.0.0.1:55439; sin datos productivos.'
