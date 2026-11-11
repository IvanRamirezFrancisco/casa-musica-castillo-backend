param(
    [Parameter(Mandatory)] [string]$PrimaryHost,
    [Parameter(Mandatory)] [string]$StagingHost,
    [Parameter(Mandatory)] [string]$ExpectedStagingEndpointId,
    [ValidateSet('security_db','castillo_staging')] [string]$Database = 'castillo_staging',
    [string]$WebOrigin,
    [switch]$ValidateEndpointsOnly,
    [string]$ProvisionDatabaseAs,
    [switch]$CreateMigrator,
    [string]$RestoreBaseline,
    [switch]$SeedLegacyMfa,
    [switch]$SeedCommerceFixtures,
    [string]$FixtureUsername,
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
if ($ValidateEndpointsOnly) {
    'Endpoint de staging distinto de Primary. Falta comprobar la identidad con Neon Console.'
    return
}
if ($RestoreBaseline) {
    $RestoreBaseline = (Resolve-Path -LiteralPath $RestoreBaseline).ProviderPath
    $expectedHash = '6c489dff31801f00f2aee8b39557f43263c8e8f331f3a9de06eb26944b4839e8'
    if ((Get-FileHash -LiteralPath $RestoreBaseline -Algorithm SHA256).Hash -ne $expectedHash) {
        throw 'El respaldo no coincide con la baseline sintética V50 verificada.'
    }
}

$repo = Split-Path $PSScriptRoot -Parent
$config = Join-Path $repo 'deploy/staging/application-staging.yml'
$jar = Join-Path $repo 'target/neon-v52/auth-system-1.0.0.jar'
if ($StartBackend -and (!(Test-Path -LiteralPath $jar -PathType Leaf) -or
    (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '697ee4b9b48782a086be110efdf54bc756774ca02342c47405d910d0fee90488')) {
    throw 'Falta el JAR V52 exacto de 4db6d67 en target/neon-v52. Verifica su SHA-256 antes de arrancar.'
}
$origins = 'https://localhost'
if ($WebOrigin) {
    if ($WebOrigin -notmatch '^https://[a-zA-Z0-9.-]+(?::[0-9]+)?$') {
        throw 'El origen web de staging debe ser HTTPS, sin ruta ni parámetros.'
    }
    $origins += ',' + $WebOrigin
}

# Ejecutar con powershell -NoProfile -File. Aislar el entorno de este proceso y sus hijos.
$systemVariables = '^(Path|SystemRoot|WINDIR|ComSpec|PATHEXT|TEMP|TMP|USERPROFILE|LOCALAPPDATA|APPDATA|JAVA_HOME|ProgramFiles|ProgramFiles\(x86\)|ProgramData|PROCESSOR_ARCHITECTURE|NUMBER_OF_PROCESSORS|OS|HOMEDRIVE|HOMEPATH|USERNAME|USERDOMAIN|SystemDrive)$'
Get-ChildItem Env: | Where-Object Name -NotMatch $systemVariables | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
$role = 'castillo_staging_migrator'
$loginRole = if ($ProvisionDatabaseAs) { $ProvisionDatabaseAs } else { $role }
$secret = Read-Host "Contraseña del rol $loginRole en Neon staging" -AsSecureString
try {
    $env:PGHOST = $stage
    $env:PGPORT = '5432'
    $env:PGDATABASE = $Database
    $env:PGUSER = $loginRole
    $env:PGPASSWORD = [Net.NetworkCredential]::new('', $secret).Password
    $env:PGSSLMODE = 'verify-full'
    $env:PGSSLROOTCERT = 'system'
    $env:PGCONNECT_TIMEOUT = '10'
    $env:PGOPTIONS = '-c default_transaction_read_only=on'
    if ($ProvisionDatabaseAs) {
        $env:PGDATABASE = 'postgres'
        if ($CreateMigrator) {
            $roleCheck = @'
SELECT current_database() = 'postgres'
   AND NOT pg_is_in_recovery()
   AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolcreatedb AND rolcreaterole)
   AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'castillo_staging_migrator')
   AND NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'castillo_staging');
'@
            $canCreateRole = & psql -X -w -At -v ON_ERROR_STOP=1 -c $roleCheck 2>$null
            if ($LASTEXITCODE -ne 0 -or $canCreateRole -cne 't') {
                throw 'El rol ya existe, la base ya existe o el administrador de staging no tiene permisos suficientes.'
            }
            Remove-Item Env:PGOPTIONS
            & psql -X -w -q -v ON_ERROR_STOP=1 --single-transaction `
                -c 'CREATE ROLE castillo_staging_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE' `
                -c '\password castillo_staging_migrator' `
                -c 'GRANT castillo_staging_migrator TO CURRENT_USER WITH SET TRUE'
            if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar el rol de staging.' }
            $env:PGOPTIONS = '-c default_transaction_read_only=on'
        }
        $createCheck = @'
SELECT current_database() = 'postgres'
   AND NOT pg_is_in_recovery()
   AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolcreatedb)
   AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'castillo_staging_migrator'
               AND rolcanlogin AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb)
   AND NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'castillo_staging');
'@
        $canCreate = & psql -X -w -At -v ON_ERROR_STOP=1 -c $createCheck 2>$null
        if ($LASTEXITCODE -ne 0 -or $canCreate -cne 't') {
            throw 'Se requiere un rol administrador de staging con CREATEDB, un migrador limitado y un destino aún inexistente.'
        }
        Remove-Item Env:PGOPTIONS
        & createdb -w --maintenance-db=postgres --template=template0 --owner=castillo_staging_migrator castillo_staging
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear castillo_staging con el propietario solicitado.' }
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
    if (!$StartBackend -and !$RestoreBaseline -and !$SeedLegacyMfa -and !$SeedCommerceFixtures) { return }
    $expectedHistory = @{
        '50' = @('48|50|-1803902864|True')
        '52' = @('48|50|-1803902864|True', '49|51|-1106382170|True', '50|52|1411222195|True')
    }
    $tail = $expectedHistory[[string]$state.version]
    if (!$tail -or $state.history.Count -ne [int]$state.version - 2 -or
        $state.baselineHistorySha256 -cne 'e5a83c830bd6e62541b2460962fc7145e25b79c9a81bb22e711ac245487a3ec3') {
        throw 'El historial no coincide con la baseline V50 ni con el ensayo V52.'
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
    if (!(($state.version -eq '50' -and !$state.hasEitherV51Table) -or
          ($state.version -eq '52' -and $state.hasV51Tables -and $state.hasV52Column))) {
        throw 'Versión o estructura inesperada. Revisar Flyway antes de arrancar.'
    }
    $env:SPRING_PROFILES_ACTIVE = 'staging'
    $env:SPRING_CONFIG_ADDITIONAL_LOCATION = ([uri]$config).AbsoluteUri
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${stage}:5432/${Database}?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
    $env:SPRING_DATASOURCE_USERNAME = $role
    $env:SPRING_DATASOURCE_PASSWORD = $env:PGPASSWORD
    Remove-Item Env:PGPASSWORD
    $env:SPRING_FLYWAY_LOCATIONS = 'classpath:db/migration'
    $env:SPRING_FLYWAY_TARGET = '52'
    $env:SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL = "SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics; SET lock_timeout TO '5s'"
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
    Remove-Item Env:PGPASSWORD,Env:SPRING_DATASOURCE_PASSWORD,Env:APP_SECURITY_JWT_SECRET,Env:PICKUP_CODE_ENCRYPTION_KEY -ErrorAction SilentlyContinue
    $secret.Dispose()
}
