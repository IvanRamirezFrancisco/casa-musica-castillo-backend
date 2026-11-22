[CmdletBinding()]
param(
    [switch]$SmokeClusterOnly,
    [string]$TestFilter = '',
    [int]$PgTimeoutSec = 30,
    [int]$MvnTimeoutSec = 900
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$pgBin = 'C:\Program Files\PostgreSQL\18\bin'
$initdb = Join-Path $pgBin 'initdb.exe'
$pgCtl = Join-Path $pgBin 'pg_ctl.exe'
$pgIsReady = Join-Path $pgBin 'pg_isready.exe'
$psql = Join-Path $pgBin 'psql.exe'

foreach ($bin in @($initdb, $pgCtl, $pgIsReady, $psql)) {
    if (-not (Test-Path -LiteralPath $bin)) {
        throw "Falta binario requerido de PostgreSQL 18: $bin"
    }
}

# Puertos exclusivamente temporales; 5432 esta prohibido en este script.
$mainPort = 55439
$catPort = 55448
if ($mainPort -eq 5432 -or $catPort -eq 5432) {
    throw 'Configuracion invalida: prohibido usar el puerto original 5432.'
}

$mainDir = Join-Path $env:TEMP 'cmc_pg_main_test'
$catDir = Join-Path $env:TEMP 'cmc_pg_cat_test'
$pwMain = Join-Path $env:TEMP 'cmc_pg_main_pw.txt'
$pwCat = Join-Path $env:TEMP 'cmc_pg_cat_pw.txt'

function Format-Win32Arg {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Value)
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }
    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

function Format-Win32CommandLine {
    param([string[]]$ArgList)
    return ($ArgList | ForEach-Object { Format-Win32Arg -Value $_ }) -join ' '
}

if (-not ('CmcProcRunner' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Threading;

public static class CmcProcRunner {
    private static void KillTree(int pid) {
        try {
            var k = Process.Start(new ProcessStartInfo("taskkill.exe", "/T /F /PID " + pid) {
                UseShellExecute = false,
                CreateNoWindow = true
            });
            if (k != null) { k.WaitForExit(5000); k.Dispose(); }
        } catch {}
    }

    public static int Run(
        ProcessStartInfo psi,
        int timeoutSec,
        bool directProcessOnly,
        bool streamLive,
        out string stdOut,
        out string stdErr
    ) {
        using (var proc = new Process()) {
            proc.StartInfo = psi;
            var outSb = new StringBuilder();
            var errSb = new StringBuilder();
            var outQueue = new ConcurrentQueue<string>();
            var errQueue = new ConcurrentQueue<string>();
            var outDone = new ManualResetEventSlim(directProcessOnly);
            var errDone = new ManualResetEventSlim(directProcessOnly);

            if (!directProcessOnly) {
                proc.OutputDataReceived += (s, e) => {
                    if (e.Data == null) { outDone.Set(); }
                    else { outQueue.Enqueue(e.Data); }
                };
                proc.ErrorDataReceived += (s, e) => {
                    if (e.Data == null) { errDone.Set(); }
                    else { errQueue.Enqueue(e.Data); }
                };
            }

            if (!proc.Start()) {
                throw new InvalidOperationException("No se pudo iniciar: " + psi.FileName);
            }

            if (directProcessOnly) {
                if (!proc.WaitForExit(timeoutSec * 1000)) {
                    try { proc.Kill(); } catch {}
                    throw new TimeoutException("Timeout tras " + timeoutSec + "s esperando proceso directo: " + psi.FileName);
                }
                stdOut = "";
                stdErr = "";
                return proc.ExitCode;
            }

            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            var sw = Stopwatch.StartNew();
            while (true) {
                bool exited = proc.WaitForExit(150);
                string line;
                while (outQueue.TryDequeue(out line)) {
                    if (outSb.Length > 0) outSb.AppendLine();
                    outSb.Append(line);
                    if (streamLive) Console.Out.WriteLine(line);
                }
                while (errQueue.TryDequeue(out line)) {
                    if (errSb.Length > 0) errSb.AppendLine();
                    errSb.Append(line);
                    if (streamLive) Console.Out.WriteLine("[STDERR] " + line);
                }
                if (exited) break;
                if (sw.Elapsed.TotalSeconds > timeoutSec) {
                    KillTree(proc.Id);
                    throw new TimeoutException("Timeout tras " + timeoutSec + "s esperando: " + psi.FileName + " " + psi.Arguments);
                }
            }
            outDone.Wait(5000);
            errDone.Wait(5000);
            string rem;
            while (outQueue.TryDequeue(out rem)) {
                if (outSb.Length > 0) outSb.AppendLine();
                outSb.Append(rem);
                if (streamLive) Console.Out.WriteLine(rem);
            }
            while (errQueue.TryDequeue(out rem)) {
                if (errSb.Length > 0) errSb.AppendLine();
                errSb.Append(rem);
                if (streamLive) Console.Out.WriteLine("[STDERR] " + rem);
            }
            stdOut = outSb.ToString();
            stdErr = errSb.ToString();
            return proc.ExitCode;
        }
    }
}
'@ -ErrorAction Stop
}

function Invoke-ExternalProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$RawArguments = '',
        [Parameter(Mandatory)][int]$TimeoutSec,
        [string]$WorkingDirectory = $repoRoot,
        [hashtable]$EnvironmentVariables = @{},
        [switch]$DirectProcessOnly,
        [switch]$StreamLiveOutput
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = if (-not [string]::IsNullOrEmpty($RawArguments)) { $RawArguments } else { Format-Win32CommandLine -ArgList $ArgumentList }
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    # CRITICO: para `pg_ctl start` NO redirigir stdout/stderr, o `postgres.exe` heredara
    # el descriptor de pipe y bloqueara `WaitForExit` / lectura de streams indefinidamente.
    $psi.RedirectStandardOutput = (-not $DirectProcessOnly)
    $psi.RedirectStandardError = (-not $DirectProcessOnly)

    foreach ($k in $EnvironmentVariables.Keys) {
        $psi.EnvironmentVariables[$k] = [string]$EnvironmentVariables[$k]
    }

    $capturedOut = ''
    $capturedErr = ''
    $exitCode = [CmcProcRunner]::Run($psi, $TimeoutSec, [bool]$DirectProcessOnly, [bool]$StreamLiveOutput, [ref]$capturedOut, [ref]$capturedErr)

    return [pscustomobject]@{
        ExitCode = $exitCode
        StdOut   = $capturedOut
        StdErr   = $capturedErr
    }
}

function Assert-TempEnvironmentIdle {
    $listening = Get-NetTCPConnection -LocalPort $mainPort,$catPort -State Listen -ErrorAction SilentlyContinue
    if ($listening) {
        $portsFound = ($listening | Select-Object -ExpandProperty LocalPort) -join ', '
        throw "ABORT: Ya hay procesos escuchando en puertos temporales ($portsFound). Detenlos ordenadamente antes de iniciar una nueva ejecucion."
    }
    $procs = Get-CimInstance Win32_Process | Where-Object {
        $_.ProcessId -ne $PID -and ($_.Name -like 'postgres*' -or $_.Name -like 'pg_ctl*') -and $_.CommandLine -and
        ($_.CommandLine -like '*cmc_pg_main_test*' -or $_.CommandLine -like '*cmc_pg_cat_test*')
    }
    if ($procs) {
        $pids = ($procs | Select-Object -ExpandProperty ProcessId) -join ', '
        throw "ABORT: Hay procesos activos usando directorios temporales (PID $pids). No se eliminaran carpetas en uso."
    }
    foreach ($dir in @($mainDir, $catDir)) {
        if (Test-Path -LiteralPath $dir) {
            throw "ABORT: El directorio temporal ya existe ($dir). Verifica e inspeccionalo antes de reutilizarlo."
        }
    }
}

function Assert-ClusterReady {
    param([int]$Port, [string]$User, [string]$ExpectedDir)

    $readyRes = Invoke-ExternalProcess -FilePath $pgIsReady -ArgumentList @('-h', '127.0.0.1', '-p', "$Port", '-U', $User, '-d', 'postgres', '-t', "$PgTimeoutSec") -TimeoutSec $PgTimeoutSec
    if ($readyRes.ExitCode -ne 0) {
        throw "pg_isready fallo en puerto ${Port}: $($readyRes.StdOut) $($readyRes.StdErr)"
    }

    $pwdMap = @{ PGPASSWORD = $(if ($Port -eq $mainPort) { 'synthetic-test-only' } else { 'synthetic-catalog-test' }) }
    $idRes = Invoke-ExternalProcess -FilePath $psql -ArgumentList @('-h', '127.0.0.1', '-p', "$Port", '-U', $User, '-d', 'postgres', '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-c', "SELECT current_setting('port') || '|' || current_setting('data_directory');") -TimeoutSec $PgTimeoutSec -EnvironmentVariables $pwdMap
    if ($idRes.ExitCode -ne 0) {
        throw "No se pudo verificar identidad SQL en puerto ${Port}: $($idRes.StdErr)"
    }
    $parts = $idRes.StdOut.Trim() -split '\|', 2
    if ($parts.Count -lt 2) {
        throw "Salida inesperada al verificar identidad en puerto ${Port}: stdout='$($idRes.StdOut)' stderr='$($idRes.StdErr)'"
    }
    $actualPort = $parts[0].Trim()
    $actualDir = ([IO.Path]::GetFullPath($parts[1].Trim())).TrimEnd('\', '/')
    $normExpected = ([IO.Path]::GetFullPath($ExpectedDir)).TrimEnd('\', '/')
    if ($actualPort -ne "$Port" -or ($actualDir -ine $normExpected)) {
        throw "Discrepancia de identidad PostgreSQL: esperado ($Port, $normExpected), obtenido ($actualPort, $actualDir)."
    }
    Write-Output "[OK] PostgreSQL temporal $actualPort verificado en $actualDir (pg_isready + identidad SQL)."
}

function Stop-TempClusterSafely {
    param([string]$Dir, [int]$Port, [string]$Label)

    if (-not (Test-Path -LiteralPath $Dir)) {
        return $true
    }
    $pidFile = Join-Path $Dir 'postmaster.pid'
    if (-not (Test-Path -LiteralPath $pidFile)) {
        Write-Output "[CLEANUP] $Label ($Dir) no tiene postmaster.pid; no requiere stop."
        return $true
    }
    $pidLines = @(Get-Content -LiteralPath $pidFile)
    $pidVal = [int]($pidLines[0].Trim())
    $pidDir = ([IO.Path]::GetFullPath($pidLines[1].Trim())).TrimEnd('\', '/')
    $pidPort = [int]($pidLines[3].Trim())
    $normDir = ([IO.Path]::GetFullPath($Dir)).TrimEnd('\', '/')
    if ($pidPort -ne $Port -or $pidDir -ine $normDir) {
        throw "BLOQUEO DE SEGURIDAD: postmaster.pid en $Dir no coincide con ($Port, $normDir); encontrado ($pidPort, $pidDir). Abortando stop."
    }
    Write-Output "[CLEANUP] Deteniendo $Label (PID=$pidVal, puerto=$Port, modo=fast)..."
    $stopRes = Invoke-ExternalProcess -FilePath $pgCtl -ArgumentList @('-D', $Dir, '-m', 'fast', '-w', '-t', '15', 'stop') -TimeoutSec 25
    if ($stopRes.ExitCode -ne 0) {
        Write-Warning "[CLEANUP] stop fast devolvio codigo $($stopRes.ExitCode) en $Label ($($stopRes.StdErr)). Intentando modo immediate..."
        $immRes = Invoke-ExternalProcess -FilePath $pgCtl -ArgumentList @('-D', $Dir, '-m', 'immediate', '-w', '-t', '15', 'stop') -TimeoutSec 25
        if ($immRes.ExitCode -ne 0) {
            throw "No se pudo detener PostgreSQL temporal $Label en $Dir ($($immRes.StdErr))."
        }
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        $stillListening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        $stillProc = Get-Process -Id $pidVal -ErrorAction SilentlyContinue
        if (-not $stillListening -and -not $stillProc) {
            Write-Output "[CLEANUP] $Label detenido y puerto $Port liberado."
            return $true
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Puerto $Port o PID $pidVal continuan activos tras pg_ctl stop."
}

$mvnExit = 1
$cleanupErrors = New-Object System.Collections.Generic.List[string]

Write-Output "[STAGE 0] Verificando que 55439/55448 esten libres y sin procesos temporales activos..."
Assert-TempEnvironmentIdle

try {
    Write-Output "[STAGE 1] Inicializando clusteres temporales PostgreSQL 18 en %TEMP%..."
    [IO.File]::WriteAllText($pwMain, 'synthetic-test-only', [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText($pwCat, 'synthetic-catalog-test', [Text.Encoding]::ASCII)

    $r1 = Invoke-ExternalProcess -FilePath $initdb -ArgumentList @('-D', $mainDir, '-U', 'auth_test', '--auth=scram-sha-256', '--pwfile', $pwMain, '-E', 'UTF8', '--no-locale') -TimeoutSec $PgTimeoutSec
    if ($r1.ExitCode -ne 0) { throw "initdb main fallo: $($r1.StdOut) $($r1.StdErr)" }

    $r2 = Invoke-ExternalProcess -FilePath $initdb -ArgumentList @('-D', $catDir, '-U', 'catalog_search_test', '--auth=scram-sha-256', '--pwfile', $pwCat, '-E', 'UTF8', '--no-locale') -TimeoutSec $PgTimeoutSec
    if ($r2.ExitCode -ne 0) { throw "initdb cat fallo: $($r2.StdOut) $($r2.StdErr)" }

    Remove-Item -LiteralPath $pwMain,$pwCat -Force

    Write-Output "[STAGE 2] Arrancando cluster temporal main (55439) y catalog (55448) esperando solo a pg_ctl..."
    $s1 = Invoke-ExternalProcess -FilePath $pgCtl -ArgumentList @('-D', $mainDir, '-l', (Join-Path $mainDir 'pg.log'), '-w', '-t', "$PgTimeoutSec", '-o', "-p $mainPort -h 127.0.0.1", 'start') -TimeoutSec ($PgTimeoutSec + 10) -DirectProcessOnly
    if ($s1.ExitCode -ne 0) {
        $pgLog = if (Test-Path -LiteralPath (Join-Path $mainDir 'pg.log')) { Get-Content -LiteralPath (Join-Path $mainDir 'pg.log') -Raw } else { '' }
        throw "pg_ctl start main fallo (exit $($s1.ExitCode)): $pgLog"
    }

    $s2 = Invoke-ExternalProcess -FilePath $pgCtl -ArgumentList @('-D', $catDir, '-l', (Join-Path $catDir 'pg.log'), '-w', '-t', "$PgTimeoutSec", '-o', "-p $catPort -h 127.0.0.1", 'start') -TimeoutSec ($PgTimeoutSec + 10) -DirectProcessOnly
    if ($s2.ExitCode -ne 0) {
        $pgLog = if (Test-Path -LiteralPath (Join-Path $catDir 'pg.log')) { Get-Content -LiteralPath (Join-Path $catDir 'pg.log') -Raw } else { '' }
        throw "pg_ctl start cat fallo (exit $($s2.ExitCode)): $pgLog"
    }

    Write-Output "[STAGE 3] Comprobando disponibilidad real e identidad de ambos clusteres..."
    Assert-ClusterReady -Port $mainPort -User 'auth_test' -ExpectedDir $mainDir
    Assert-ClusterReady -Port $catPort -User 'catalog_search_test' -ExpectedDir $catDir

    Write-Output "[STAGE 4] Creando bases de prueba aisladas..."
    foreach ($db in @('backend_hardening_test','activation_upgrade_test','auth_challenges_test','password_upgrade_test','schema50_upgrade_test')) {
        $dbRes = Invoke-ExternalProcess -FilePath $psql -ArgumentList @('-h', '127.0.0.1', '-p', "$mainPort", '-U', 'auth_test', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', "CREATE DATABASE $db;") -TimeoutSec $PgTimeoutSec -EnvironmentVariables @{ PGPASSWORD = 'synthetic-test-only' }
        if ($dbRes.ExitCode -ne 0) { throw "CREATE DATABASE $db fallo: $($dbRes.StdErr)" }
    }
    $catDbRes = Invoke-ExternalProcess -FilePath $psql -ArgumentList @('-h', '127.0.0.1', '-p', "$catPort", '-U', 'catalog_search_test', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE DATABASE catalog_search_test;') -TimeoutSec $PgTimeoutSec -EnvironmentVariables @{ PGPASSWORD = 'synthetic-catalog-test' }
    if ($catDbRes.ExitCode -ne 0) { throw "CREATE DATABASE catalog_search_test fallo: $($catDbRes.StdErr)" }

    if ($SmokeClusterOnly) {
        Write-Output "[STAGE 5] SmokeClusterOnly activo: omitiendo Maven y pasando a apagado ordenado."
        $mvnExit = 0
    } else {
        $mvnCmd = (Get-Command 'mvn.cmd' -ErrorAction Stop).Source
        $innerCmd = "`"$mvnCmd`" -B --no-transfer-progress test"
        if (-not [string]::IsNullOrWhiteSpace($TestFilter)) {
            $innerCmd += " `"-Dtest=$TestFilter`""
        }
        $rawMvnArgs = "/d /s /c `"$innerCmd`""
        $mvnEnv = @{
            CMC_CATALOG_TEST_PASSWORD = 'synthetic-catalog-test'
            STORAGE_PROVIDER          = 'LOCAL'
            MERCADO_PAGO_ENABLED      = 'false'
            SPRING_MAIL_HOST          = '127.0.0.1'
            SPRING_MAIL_PORT          = '1'
        }
        Write-Output "[STAGE 5] Ejecutando Maven: $innerCmd"
        $mvnRes = Invoke-ExternalProcess -FilePath 'cmd.exe' -RawArguments $rawMvnArgs -TimeoutSec $MvnTimeoutSec -WorkingDirectory $repoRoot -EnvironmentVariables $mvnEnv -StreamLiveOutput
        $mvnExit = $mvnRes.ExitCode
        Write-Output "[STAGE 5] Maven finalizo con codigo de salida: $mvnExit"
    }
} finally {
    foreach ($pw in @($pwMain, $pwCat)) {
        if (Test-Path -LiteralPath $pw) { Remove-Item -LiteralPath $pw -Force }
    }

    $mainStopped = $false
    $catStopped = $false
    try { $mainStopped = Stop-TempClusterSafely -Dir $mainDir -Port $mainPort -Label 'temp-main-55439' } catch { $cleanupErrors.Add($_.Exception.Message) }
    try { $catStopped = Stop-TempClusterSafely -Dir $catDir -Port $catPort -Label 'temp-cat-55448' } catch { $cleanupErrors.Add($_.Exception.Message) }

    if ($mainStopped -and (Test-Path -LiteralPath $mainDir)) {
        Remove-Item -LiteralPath $mainDir -Recurse -Force
    }
    if ($catStopped -and (Test-Path -LiteralPath $catDir)) {
        Remove-Item -LiteralPath $catDir -Recurse -Force
    }

    Write-Output ("CLEANUP mainExists=" + (Test-Path -LiteralPath $mainDir) + " catExists=" + (Test-Path -LiteralPath $catDir))
    $openTemp = @(Get-NetTCPConnection -LocalPort $mainPort,$catPort -State Listen -ErrorAction SilentlyContinue)
    Write-Output ("PORTS_TEMP_REMAINING=" + $openTemp.Count)
    $open5432 = @(Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue)
    Write-Output ("PORT_5432_ALIVE=" + ($open5432.Count -gt 0))
    if ($cleanupErrors.Count -gt 0) {
        throw ("Errores durante la limpieza ordenada: " + ($cleanupErrors -join ' | '))
    }
}

exit $mvnExit
