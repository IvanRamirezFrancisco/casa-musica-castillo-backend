param(
    [Parameter(Mandatory)] [string]$PrimaryHost,
    [Parameter(Mandatory)] [string]$StagingHost,
    [Parameter(Mandatory)] [string]$ExpectedStagingEndpointId,
    [ValidateSet('security_db','castillo_staging')] [string]$Database = 'castillo_staging',
    [string]$WebOrigin,
    [switch]$ValidateEndpointsOnly,
    [switch]$StartBackend
)
$ErrorActionPreference = 'Stop'
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

$repo = Split-Path $PSScriptRoot -Parent
$config = Join-Path $repo 'deploy/staging/application-staging.yml'
$jar = Join-Path $repo 'target/auth-system-1.0.0.jar'
if ($StartBackend -and !(Test-Path -LiteralPath $jar)) { throw 'Compila el JAR candidato antes de iniciar staging.' }
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
$secret = Read-Host 'Contraseña del rol exclusivo de Neon staging' -AsSecureString
try {
    $env:PGHOST = $stage
    $env:PGPORT = '5432'
    $env:PGDATABASE = $Database
    $env:PGUSER = $role
    $env:PGPASSWORD = [Net.NetworkCredential]::new('', $secret).Password
    $env:PGSSLMODE = 'verify-full'
    $env:PGSSLROOTCERT = 'system'
    $env:PGCONNECT_TIMEOUT = '10'
    $query = @'
SELECT json_build_object(
  'database', current_database(), 'role', current_user, 'postgres', current_setting('server_version'),
  'version', (SELECT version FROM public.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1),
  'failed', (SELECT count(*) FROM public.flyway_schema_history WHERE NOT success),
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
