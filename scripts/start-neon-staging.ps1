param(
    [Parameter(Mandatory)] [string]$PrimaryHost,
    [Parameter(Mandatory)] [string]$StagingHost,
    [Parameter(Mandatory)] [string]$ExpectedStagingEndpointId,
    [string]$CaBundlePath,
    [ValidatePattern('^[0-9a-fA-F]{64}$')] [string]$ExpectedLegacyMfaFixtureSha256,
    [ValidateSet('security_db','castillo_staging')] [string]$Database = 'castillo_staging',
    [string]$WebOrigin,
    [switch]$ValidateEndpointsOnly,
    [string]$ProvisionDatabaseAs,
    [switch]$CreateMigrator,
    [string]$RestoreBaseline,
    [switch]$SeedLegacyMfa,
    [switch]$SeedCommerceFixtures,
    [string]$FixtureUsername,
    [ValidateSet('52','53')] [string]$FlywayTarget = '52',
    [switch]$StartBackend
)
$ErrorActionPreference = 'Stop'
if (($StartBackend -or $RestoreBaseline -or $ProvisionDatabaseAs -or $SeedLegacyMfa -or $SeedCommerceFixtures) -and
    $Database -cne 'castillo_staging') {
    throw 'La creación, restauración y arranque solo están autorizados en castillo_staging.'
}
if ($ProvisionDatabaseAs -and ($ProvisionDatabaseAs -notmatch '^[a-zA-Z_][a-zA-Z0-9_-]{0,62}$' -or
    $StartBackend -or $RestoreBaseline -or $SeedLegacyMfa -or $SeedCommerceFixtures)) {
    throw 'Usa un rol válido y ejecuta la creación de la base por separado.'
}
if ($CreateMigrator -and !$ProvisionDatabaseAs) {
    throw 'La creación del rol requiere un administrador de staging identificado.'
}
if ($FixtureUsername -and (!$SeedCommerceFixtures -or $FixtureUsername -notmatch '^stg-[a-z0-9-]{1,26}$')) {
    throw 'Indica exclusivamente un nombre sintético stg- válido para el fixture de pedidos.'
}
if ($FlywayTarget -eq '53' -and (!$StartBackend -or $RestoreBaseline -or $SeedLegacyMfa -or $SeedCommerceFixtures)) {
    throw 'V53 requiere un arranque separado sobre la base ya ensayada con V52.'
}
$primary = $PrimaryHost.Trim().ToLowerInvariant()
$stage = $StagingHost.Trim().ToLowerInvariant()
$expected = $ExpectedStagingEndpointId.Trim().ToLowerInvariant()
$hostPattern = '^ep-[a-z0-9-]+\.[a-z0-9.-]+\.neon\.tech$'
if ($primary -notmatch $hostPattern -or $stage -notmatch $hostPattern -or $expected -notmatch '^ep-[a-z0-9-]+$') {
    throw 'Introduce los hostnames privados de Connect y el ID de endpoint de la rama staging.'
}
$primaryEndpoint = $primary.Split('.')[0] -replace '-pooler$', ''
$stageEndpoint = $stage.Split('.')[0]
if ($stageEndpoint.EndsWith('-pooler') -or $stageEndpoint -ne $expected -or $stageEndpoint -eq $primaryEndpoint) {
    throw 'Destino rechazado. Se requiere el endpoint directo de staging, distinto de Primary.'
}
$caBundle = $null
if ($CaBundlePath) {
    $caFile = Get-Item -LiteralPath $CaBundlePath -ErrorAction SilentlyContinue
    if ($caFile -isnot [IO.FileInfo] -or
        (Get-Content -LiteralPath $caFile.FullName -Raw) -notmatch '(?s)-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----') {
        throw 'CaBundlePath debe apuntar a un archivo PEM de certificados CA existente.'
    }
    $caBundle = $caFile.FullName
}
if ($ValidateEndpointsOnly) {
    'Endpoint de staging distinto de Primary. Falta comprobar la identidad con Neon Console.'
    return
}
if (!$caBundle) { throw 'Indica -CaBundlePath con el PEM de CA autorizado para conectar a Neon.' }
if ($RestoreBaseline) {
    $RestoreBaseline = (Resolve-Path -LiteralPath $RestoreBaseline).ProviderPath
    $expectedHash = '6c489dff31801f00f2aee8b39557f43263c8e8f331f3a9de06eb26944b4839e8'
    if ((Get-FileHash -LiteralPath $RestoreBaseline -Algorithm SHA256).Hash -ne $expectedHash) {
        throw 'El respaldo no coincide con la baseline sintética V50 verificada.'
    }
}

$repo = Split-Path $PSScriptRoot -Parent
$config = Join-Path $repo 'deploy/staging/application-staging.yml'
$candidateHashes = @{
    '52' = '697ee4b9b48782a086be110efdf54bc756774ca02342c47405d910d0fee90488'
    '53' = '090fad85a77677b6a98ebaa050e6f1e7e6ebc06f3f53e47611c868e0f6b4c485'
}
$jar = Join-Path $repo "target/neon-v$FlywayTarget/auth-system-1.0.0.jar"
$baseline = Join-Path $repo 'src/test/resources/db/test-baseline'
if ($StartBackend) {
    if (!(Test-Path -LiteralPath $jar -PathType Leaf) -or
        (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $candidateHashes[$FlywayTarget]) {
        throw "Falta el JAR verificado V$FlywayTarget en target/neon-v$FlywayTarget o no coincide su SHA-256."
    }
    if (!(Test-Path -LiteralPath (Join-Path $baseline 'V1__legacy_test_schema.sql') -PathType Leaf)) {
        throw 'Falta la migración sintética V1 necesaria para validar el historial restaurado.'
    }
}
$origins = 'https://localhost'
if ($WebOrigin) {
    if ($WebOrigin -notmatch '^https://[a-zA-Z0-9.-]+(?::[0-9]+)?$') {
        throw 'El origen web de staging debe ser HTTPS, sin ruta ni parámetros.'
    }
    $origins += ',' + $WebOrigin
}

function New-NeonStagingMigrator([Security.SecureString]$Password) {
    $buffer = [char[]]::new($Password.Length)
    $pointer = [IntPtr]::Zero
    $process = $null
    $invalidPassword = $false
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
        [Runtime.InteropServices.Marshal]::Copy($pointer, $buffer, 0, $buffer.Length)
        # Windows libpq reads PGPASSWORD through its native code page; use ASCII for lossless reuse.
        foreach ($character in $buffer) {
            if ([int]$character -eq 0 -or [int]$character -gt 127) {
                $invalidPassword = $true
                throw 'Contraseña incompatible con la codificación de Windows libpq.'
            }
        }

        $psql = Get-Command psql -CommandType Application | Select-Object -First 1
        $start = [Diagnostics.ProcessStartInfo]::new($psql.Source, '-X -w -q -v ON_ERROR_STOP=1 -f -')
        $start.UseShellExecute = $false
        $start.RedirectStandardInput = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $start.EnvironmentVariables['PGCLIENTENCODING'] = 'UTF8'
        $process = [Diagnostics.Process]::Start($start)
        # PostgreSQL errors can echo SQL. Drain both streams without exposing their content.
        $stdout = $process.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
        $stderr = $process.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
        $writer = [IO.StreamWriter]::new($process.StandardInput.BaseStream, [Text.UTF8Encoding]::new($false, $true))
        try {
            $writer.Write("CREATE ROLE castillo_staging_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD E'")
            foreach ($character in $buffer) {
                switch ($character) {
                    "'" { $writer.Write("''") }
                    '\' { $writer.Write('\\') }
                    "`r" { $writer.Write('\r') }
                    "`n" { $writer.Write('\n') }
                    default { $writer.Write([char]$character) }
                }
            }
            $writer.WriteLine("';")
        } finally {
            $writer.Dispose()
        }
        $process.WaitForExit()
        $null = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'Falló CREATE ROLE.' }
    } catch {
        if ($invalidPassword) { throw 'Genera una contraseña aleatoria ASCII para el migrador en Windows.' }
        throw 'No se confirmó la creación del migrador. Repite la inspección antes de reintentar.'
    } finally {
        [Array]::Clear($buffer, 0, $buffer.Length)
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        if ($process) {
            if (!$process.HasExited) { $process.Kill(); $process.WaitForExit() }
            $process.Dispose()
        }
    }
}

function Get-StagingJdbcSessionParameters {
    $schema = [uri]::EscapeDataString('public,auth,security,catalog,sales,customer,ops,analytics')
    $options = [uri]::EscapeDataString('-c lock_timeout=5s')
    return "currentSchema=$schema&options=$options"
}

function Write-StagingSpringDefaults([string]$JarPath, [string]$Destination) {
    if (!('System.IO.Compression.ZipFile' -as [type])) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
    }
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry('BOOT-INF/classes/application.yml')
        if (!$entry) { throw 'El JAR no contiene su configuración base esperada.' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $defaults = $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    } finally { $archive.Dispose() }

    $legacySetting = [regex]::new('(?m)^[ \t]+connection-init-sql:[^\r\n]*\r?\n')
    if ($legacySetting.Matches($defaults).Count -ne 1) {
        throw 'La configuración Hikari del JAR no coincide con la revisión esperada.'
    }
    [IO.File]::WriteAllText($Destination, $legacySetting.Replace($defaults, ''), [Text.UTF8Encoding]::new($false))
}

# Ejecutar con powershell -NoProfile -File. Aislar el entorno de este proceso y sus hijos.
$systemVariables = '^(Path|SystemRoot|WINDIR|ComSpec|PATHEXT|TEMP|TMP|USERPROFILE|LOCALAPPDATA|APPDATA|JAVA_HOME|ProgramFiles|ProgramFiles\(x86\)|ProgramData|PROCESSOR_ARCHITECTURE|NUMBER_OF_PROCESSORS|OS|HOMEDRIVE|HOMEPATH|USERNAME|USERDOMAIN|SystemDrive)$'
Get-ChildItem Env: | Where-Object Name -NotMatch $systemVariables | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
$role = 'castillo_staging_migrator'
$loginRole = if ($ProvisionDatabaseAs) { $ProvisionDatabaseAs } else { $role }
$secret = Read-Host "Contraseña del rol $loginRole en Neon staging" -AsSecureString
$configDir = $null
$defaultsPath = $null
try {
    if (!$secret -or $secret.Length -eq 0) { throw 'Se requiere una contraseña local del rol exclusivo de staging.' }
    $env:PGHOST = $stage
    $env:PGPORT = '5432'
    $env:PGDATABASE = $Database
    $env:PGUSER = $loginRole
    $env:PGPASSWORD = [Net.NetworkCredential]::new('', $secret).Password
    $env:PGPASSFILE = 'NUL'
    $env:PGSSLMODE = 'verify-full'
    $env:PGSSLROOTCERT = $caBundle
    $env:PGGSSENCMODE = 'disable'
    $env:PGCONNECT_TIMEOUT = '10'
    $env:PGOPTIONS = '-c default_transaction_read_only=on'
    if ($ProvisionDatabaseAs) {
        $env:PGDATABASE = 'postgres'
        $provisionQuery = @'
SELECT json_build_object(
  'validSession', current_database() = 'postgres' AND NOT pg_is_in_recovery(),
  'canCreateRole', admin.rolcreaterole,
  'canCreateDatabase', admin.rolcreatedb,
  'roleExists', migrator.oid IS NOT NULL,
  'roleIsLimited', migrator.rolcanlogin AND NOT migrator.rolsuper AND NOT migrator.rolcreatedb
                   AND NOT migrator.rolcreaterole AND NOT migrator.rolreplication AND NOT migrator.rolbypassrls
                   AND NOT EXISTS (SELECT 1 FROM pg_roles AS elevated WHERE elevated.rolname = 'neon_superuser'
                                   AND pg_has_role(migrator.oid, elevated.oid, 'MEMBER')),
  'canSetMigrator', pg_has_role(admin.oid, migrator.oid, 'SET'),
  'databaseOwner', (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'castillo_staging')
)
FROM pg_roles AS admin
LEFT JOIN pg_roles AS migrator ON migrator.rolname = 'castillo_staging_migrator'
WHERE admin.rolname = current_user;
'@
        function Get-NeonProvisionState {
            $result = & psql -X -w -At -v ON_ERROR_STOP=1 -c $provisionQuery 2>$null
            if ($LASTEXITCODE -ne 0 -or !$result) { throw 'No se pudo inspeccionar el rol y la base de staging.' }
            $state = $result | ConvertFrom-Json
            if (!$state.validSession) { throw 'La sesión administrativa de staging no es válida.' }
            return $state
        }
        $provision = Get-NeonProvisionState
        if ($provision.roleExists -and !$provision.roleIsLimited) {
            throw 'El migrador existente tiene privilegios inesperados. Se detuvo el aprovisionamiento.'
        }
        if ($provision.databaseOwner) {
            if (!$provision.roleIsLimited -or $provision.databaseOwner -cne $role) {
                throw 'castillo_staging ya existe con un propietario inesperado. Se detuvo el aprovisionamiento.'
            }
            'Base castillo_staging existente con el propietario esperado.'
            return
        }
        if (!$provision.canCreateDatabase) { throw 'Se requiere un administrador de staging con CREATEDB.' }
        if (!$provision.roleExists) {
            if (!$CreateMigrator -or !$provision.canCreateRole) {
                throw 'Falta el migrador. Se requiere -CreateMigrator y un administrador de staging con CREATEROLE.'
            }
            $newPassword = Read-Host 'Nueva contraseña exclusiva de castillo_staging_migrator' -AsSecureString
            try {
                if (!$newPassword -or !$newPassword.Length) { throw 'Se requiere una contraseña nueva para el migrador.' }
                Remove-Item Env:PGOPTIONS
                New-NeonStagingMigrator $newPassword
            } finally {
                if ($newPassword) { $newPassword.Dispose() }
            }
            $env:PGOPTIONS = '-c default_transaction_read_only=on'
            $provision = Get-NeonProvisionState
            if (!$provision.roleIsLimited -or $provision.databaseOwner) {
                throw 'El estado cambió tras crear el rol. Inspecciona staging antes de continuar.'
            }
        }
        if (!$provision.canSetMigrator) {
            Remove-Item Env:PGOPTIONS
            & psql -X -w -q -v ON_ERROR_STOP=1 -c 'GRANT castillo_staging_migrator TO CURRENT_USER WITH SET TRUE'
            if ($LASTEXITCODE -ne 0) { throw 'El administrador no pudo asumir el rol propietario. El rol se conserva.' }
        }
        Remove-Item Env:PGOPTIONS -ErrorAction SilentlyContinue
        & createdb -w --maintenance-db=postgres --template=template0 --owner=castillo_staging_migrator castillo_staging
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear castillo_staging con el propietario solicitado.' }
        $env:PGOPTIONS = '-c default_transaction_read_only=on'
        $provision = Get-NeonProvisionState
        if (!$provision.roleIsLimited -or $provision.databaseOwner -cne $role) {
            throw 'No se pudo confirmar el propietario de castillo_staging tras crearla.'
        }
        'Base castillo_staging creada.'
        return
    }
    if ($RestoreBaseline) {
        $identityQuery = @'
SELECT json_build_object(
  'database', current_database(),
  'role', current_user,
  'ownsDatabase', pg_get_userbyid(datdba) = current_user,
  'canCreate', has_database_privilege(current_user, current_database(), 'CREATE'),
  'canWritePublic', has_schema_privilege(current_user, 'public', 'CREATE'),
  'empty', NOT EXISTS (
    SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('public','information_schema')
      AND nspname !~ '^pg_'
  ) AND NOT EXISTS (
    SELECT 1 FROM pg_class WHERE relnamespace = 'public'::regnamespace
  ) AND NOT EXISTS (
    SELECT 1 FROM pg_extension WHERE extname NOT IN ('plpgsql', 'neon')
  )
) FROM pg_database WHERE datname = current_database();
'@
        $raw = & psql -X -w -At -v ON_ERROR_STOP=1 -c $identityQuery 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Falló la conexión verificada a castillo_staging.' }
        $identity = $raw | ConvertFrom-Json
        if ($identity.database -cne 'castillo_staging' -or $identity.role -cne $role -or
            !$identity.ownsDatabase -or !$identity.canCreate -or !$identity.canWritePublic -or !$identity.empty) {
            throw 'La base debe estar vacía y pertenecer al rol de migración. No se restauró ningún dato.'
        }
        Remove-Item Env:PGOPTIONS
        & pg_restore -w -d castillo_staging --no-owner --no-acl --single-transaction --exit-on-error $RestoreBaseline 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Falló la restauración transaccional de la baseline V50.' }
        $env:PGOPTIONS = '-c default_transaction_read_only=on'
        'Restauración transaccional de la baseline sintética V50 completada.'
    }
    $query = @'
SELECT json_build_object(
  'database', current_database(), 'role', current_user, 'postgres', current_setting('server_version'),
  'version', (SELECT version FROM public.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1),
  'failed', (SELECT count(*) FROM public.flyway_schema_history WHERE NOT success),
  'history', (SELECT json_agg(json_build_array(installed_rank, version, checksum, success) ORDER BY installed_rank)
              FROM public.flyway_schema_history WHERE version IS NOT NULL),
  'baselineHistorySha256', (SELECT encode(sha256(convert_to(string_agg(
      concat_ws('|', installed_rank, coalesce(version,'-'), coalesce(checksum::text,'-'),
                CASE WHEN success THEN 't' ELSE 'f' END), chr(10) ORDER BY installed_rank), 'UTF8')), 'hex')
      FROM public.flyway_schema_history WHERE installed_rank <= 48),
  'legacyMfaFixtureSha256', (SELECT encode(sha256(convert_to(
      json_build_array(id, email, enabled, two_factor_enabled, google_auth_enabled, google_auth_secret,
                       password, two_factor_type)::text, 'UTF8')), 'hex')
      FROM auth.users WHERE username = 'synthetic_neon_v50_totp' AND email = 'neon-v50-totp@example.invalid'),
  'canCreateInAuth', has_schema_privilege(current_user, 'auth', 'CREATE'),
  'canReferenceUsers', has_table_privilege(current_user, 'auth.users', 'REFERENCES'),
  'canWriteHistory', has_table_privilege(current_user, 'public.flyway_schema_history', 'INSERT')
                     AND has_table_privilege(current_user, 'public.flyway_schema_history', 'UPDATE'),
  'hasV51Tables', to_regclass('auth.login_challenges') IS NOT NULL AND to_regclass('auth.login_factor_limits') IS NOT NULL,
  'hasV52Column', EXISTS (SELECT 1 FROM information_schema.columns
                          WHERE table_schema = 'auth' AND table_name = 'login_factor_limits' AND column_name = 'last_verified_totp_slot'),
  'v53Columns', (SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'auth' AND (
                   (table_name = 'users' AND column_name = 'email_verified_at') OR
                   (table_name = 'verification_tokens' AND column_name = 'recipient_email')
                 )),
  'hasEitherV51Table', to_regclass('auth.login_challenges') IS NOT NULL OR to_regclass('auth.login_factor_limits') IS NOT NULL
);
'@
    $raw = & psql -X -w -At -v ON_ERROR_STOP=1 -c $query 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Falló la lectura privada de staging o su historial Flyway. Spring permanece detenido.' }
    $state = $raw | ConvertFrom-Json
    if ($state.database -ne $Database -or $state.role -ne $role -or $state.failed -ne 0) {
        throw 'Identidad o historial Flyway inesperados. Spring permanece detenido.'
    }
    $state | ConvertTo-Json
    if ($ExpectedLegacyMfaFixtureSha256 -and $state.legacyMfaFixtureSha256 -ne $ExpectedLegacyMfaFixtureSha256) {
        throw 'La huella del fixture TOTP no coincide con la esperada. Spring permanece detenido.'
    }
    if ($Database -eq 'security_db') { return }
    $expectedHistory = @{
        '50' = @('48|50|-1803902864|True')
        '51' = @('48|50|-1803902864|True', '49|51|-1106382170|True')
        '52' = @('48|50|-1803902864|True', '49|51|-1106382170|True', '50|52|1411222195|True')
        '53' = @('48|50|-1803902864|True', '49|51|-1106382170|True', '50|52|1411222195|True', '51|53|-774895556|True')
    }
    $tail = $expectedHistory[[string]$state.version]
    if (!$tail -or $state.history.Count -ne [int]$state.version - 2 -or
        $state.baselineHistorySha256 -cne 'e5a83c830bd6e62541b2460962fc7145e25b79c9a81bb22e711ac245487a3ec3') {
        throw 'El historial no coincide con las revisiones sintéticas V50 a V53.'
    }
    for ($offset = 1; $offset -le $tail.Count; $offset++) {
        if (($state.history[-$offset] -join '|') -cne $tail[-$offset]) {
            throw 'Flyway registra un checksum, rango o resultado inesperado.'
        }
    }
    if ($SeedLegacyMfa) {
        if ($state.version -cne '50' -or $state.hasEitherV51Table) {
            throw 'La cuenta de prueba de compatibilidad debe prepararse antes de migrar desde V50.'
        }
        $seedQuery = @'
INSERT INTO auth.users (username, first_name, last_name, email, password, enabled,
    account_non_expired, account_non_locked, credentials_non_expired, two_factor_enabled,
    google_auth_enabled, google_auth_secret, email_enabled, backup_codes_enabled, is_customer, two_factor_type, created_at)
VALUES ('synthetic_neon_v50_totp', 'Synthetic', 'Neon V50', 'neon-v50-totp@example.invalid',
    public.crypt(encode(public.gen_random_bytes(32), 'hex'), public.gen_salt('bf', 10)),
    true, true, true, true, true, true,
    (SELECT string_agg(substr('ABCDEFGHIJKLMNOPQRSTUVWXYZ234567',
        get_byte(public.gen_random_bytes(1), 0) % 32 + 1, 1), '') FROM generate_series(1, 32)),
    false, false, true, 'GOOGLE_AUTHENTICATOR', now())
ON CONFLICT (username) DO NOTHING;
'@
        Remove-Item Env:PGOPTIONS
        & psql -X -w -q -v ON_ERROR_STOP=1 -c $seedQuery 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar el fixture TOTP sintético en V50.' }
        $env:PGOPTIONS = '-c default_transaction_read_only=on'
        $raw = & psql -X -w -At -v ON_ERROR_STOP=1 -c $query 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo comprobar el fixture TOTP sintético.' }
        $state = $raw | ConvertFrom-Json
        if (!$state.legacyMfaFixtureSha256) { throw 'Falta el fixture TOTP de identidad esperada.' }
        $state | ConvertTo-Json
    }
    if ($SeedCommerceFixtures) {
        if ($state.version -cne '52' -or !$state.hasV51Tables -or !$state.hasV52Column) {
            throw 'El catálogo sintético se prepara exclusivamente después de verificar V52.'
        }
        $fixtureArgs = @()
        if ($FixtureUsername) { $fixtureArgs = @('-v', "fixture_username=$FixtureUsername") }
        Remove-Item Env:PGOPTIONS
        & psql -X -w -q -v ON_ERROR_STOP=1 @fixtureArgs -f (Join-Path $repo 'deploy/staging/fixtures.sql') 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo cargar el catálogo y pedido de prueba.' }
        'Fixtures sintéticos de catálogo preparados.'
    }
    if (!$StartBackend) { return }
    if (!$state.canCreateInAuth -or !$state.canReferenceUsers -or !$state.canWriteHistory) {
        throw 'El rol no tiene los permisos mínimos esperados para el ensayo.'
    }
    $hasV52Schema = $state.hasV51Tables -and $state.hasV52Column
    if ($FlywayTarget -eq '52') {
        if ($state.v53Columns -ne 0 -or !(($state.version -eq '50' -and !$state.hasEitherV51Table) -or
            ($state.version -eq '51' -and $state.hasV51Tables -and !$state.hasV52Column) -or
            ($state.version -eq '52' -and $hasV52Schema))) {
            throw 'El candidato V52 requiere una baseline V50, V51 o V52 íntegra.'
        }
    } elseif (!$hasV52Schema -or !(($state.version -eq '52' -and $state.v53Columns -eq 0) -or
        ($state.version -eq '53' -and $state.v53Columns -eq 2))) {
        throw 'El candidato V53 requiere un V52 íntegro o un V53 ya verificado.'
    }
    $configDir = [IO.Directory]::CreateDirectory((Join-Path ([IO.Path]::GetTempPath()) ('cmc-neon-spring-' + [guid]::NewGuid().ToString('N')))).FullName
    $defaultsPath = Join-Path $configDir 'application.yml'
    Write-StagingSpringDefaults $jar $defaultsPath
    $env:SPRING_PROFILES_ACTIVE = 'staging'
    # Explicit locations exclude the bundled defaults and any configuration in the current directory.
    $env:SPRING_CONFIG_LOCATION = @(([uri]$defaultsPath).AbsoluteUri, ([uri]$config).AbsoluteUri) -join ','
    # pgJDBC loads sslrootcert with its own LibPQFactory, independently of libpq.
    $jdbcCaBundle = [uri]::EscapeDataString($caBundle)
    $jdbcSession = Get-StagingJdbcSessionParameters
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${stage}:5432/${Database}?sslmode=verify-full&sslfactory=org.postgresql.ssl.LibPQFactory&sslrootcert=$jdbcCaBundle&$jdbcSession"
    $env:SPRING_DATASOURCE_USERNAME = $role
    $env:SPRING_DATASOURCE_PASSWORD = $env:PGPASSWORD
    Remove-Item Env:PGPASSWORD
    $env:SPRING_FLYWAY_LOCATIONS = 'filesystem:' + $baseline.Replace('\','/') + ',classpath:db/migration'
    $env:SPRING_FLYWAY_TARGET = $FlywayTarget
    $env:STAGING_PUBLIC_ORIGIN = if ($WebOrigin) { $WebOrigin } else { 'https://castillo-neon-staging.invalid' }
    $env:STAGING_ALLOWED_ORIGINS = $origins
    $env:STAGING_SMTP_HOST = '127.0.0.1'
    $env:STAGING_SMTP_PORT = '11025'
    $env:STAGING_BACKEND_PORT = '18082'
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:APP_SECURITY_JWT_SECRET = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $env:PICKUP_CODE_ENCRYPTION_KEY = [guid]::NewGuid().ToString('N')
    & java '-Xmx384m' '-Xms256m' '-XX:+UseG1GC' -jar $jar
    if ($LASTEXITCODE -ne 0) { throw 'Spring staging terminó con error.' }
} finally {
    Remove-Item Env:PGPASSWORD,Env:PGPASSFILE,Env:SPRING_DATASOURCE_PASSWORD,Env:APP_SECURITY_JWT_SECRET,Env:PICKUP_CODE_ENCRYPTION_KEY -ErrorAction SilentlyContinue
    if ($secret) { $secret.Dispose() }
    if ($defaultsPath) { [IO.File]::Delete($defaultsPath) }
    if ($configDir) { [IO.Directory]::Delete($configDir) }
}
