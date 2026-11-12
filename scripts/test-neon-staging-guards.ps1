# Run in a child PowerShell process. The launcher deliberately clears inherited environment variables.
param([string]$CaBundlePath)

$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot 'start-neon-staging.ps1'
$primary = 'ep-production.example.aws.neon.tech'
$staging = 'ep-staging.example.aws.neon.tech'
$common = @{
    PrimaryHost = $primary
    StagingHost = $staging
    ExpectedStagingEndpointId = 'ep-staging'
}
if ($CaBundlePath) {
    $common.CaBundlePath = (Get-Item -LiteralPath $CaBundlePath).FullName
}
$global:CmcNeonGuardTestState = @{
    PromptCount = 0
    ConnectionCount = 0
    CreatedbCount = 0
    GrantCount = 0
    Password = [guid]::NewGuid().ToString('N')
    Staging = $staging
    CaBundle = $common.CaBundlePath
    Provision = @{
        validSession = $true
        canCreateDatabase = $true
        canCreateRole = $true
        roleExists = $true
        roleIsLimited = $true
        canSetMigrator = $true
        databaseOwner = $null
    }
}

function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    if (!$AsSecureString) { throw 'The launcher requested a password without masking.' }
    $global:CmcNeonGuardTestState.PromptCount++
    ConvertTo-SecureString $global:CmcNeonGuardTestState.Password -AsPlainText -Force
}

function psql {
    $global:CmcNeonGuardTestState.ConnectionCount++
    $syntheticPassword = $global:CmcNeonGuardTestState.Password
    $grant = $args -contains 'GRANT castillo_staging_migrator TO CURRENT_USER WITH SET TRUE'
    $expectedOptions = if (!$grant) { '-c default_transaction_read_only=on' }
    if ($env:PGHOST -cne $global:CmcNeonGuardTestState.Staging -or $env:PGDATABASE -cne 'postgres' -or
        $env:PGUSER -cne 'admin_staging' -or $env:PGSSLMODE -cne 'verify-full' -or
        $env:PGSSLROOTCERT -cne $global:CmcNeonGuardTestState.CaBundle -or
        $env:SSL_CERT_FILE -or $env:PGOPTIONS -ne $expectedOptions -or
        $env:SPRING_DATASOURCE_URL -or $env:DATABASE_URL -or $env:PGSERVICE -or $env:PGPASSFILE -cne 'NUL' -or
        $env:JAVA_TOOL_OPTIONS -or $env:PGPASSWORD -cne $syntheticPassword -or
        ($args -join ' ').Contains($syntheticPassword)) {
        throw 'The launcher did not isolate the connection or supplied a password as an argument.'
    }
    $global:LASTEXITCODE = 0
    if ($grant) {
        $global:CmcNeonGuardTestState.GrantCount++
        $global:CmcNeonGuardTestState.Provision.canSetMigrator = $true
    } else {
        $global:CmcNeonGuardTestState.Provision | ConvertTo-Json -Compress
    }
}

function createdb {
    $global:CmcNeonGuardTestState.CreatedbCount++
    $syntheticPassword = $global:CmcNeonGuardTestState.Password
    if ($env:PGHOST -cne $global:CmcNeonGuardTestState.Staging -or
        $env:PGSSLROOTCERT -cne $global:CmcNeonGuardTestState.CaBundle -or
        $env:PGSSLMODE -cne 'verify-full' -or $env:PGOPTIONS -or
        $args[-1] -cne 'castillo_staging' -or $args -notcontains '--maintenance-db=postgres' -or
        ($args -join ' ').Contains($syntheticPassword)) {
        throw 'createdb did not receive the isolated TLS configuration or exposed a password.'
    }
    $global:CmcNeonGuardTestState.Provision.databaseOwner = 'castillo_staging_migrator'
    $global:LASTEXITCODE = 0
}

function Assert-Rejected($parameters, [string]$message) {
    try { & $launcher @parameters }
    catch {
        if ($_.Exception.Message -like "*$message*") { return }
        throw
    }
    throw "Expected rejection: $message"
}

$env:PGHOST = $primary
$env:PGDATABASE = 'security_db'
$env:PGPASSWORD = 'unused-synthetic-inherited-marker'
$env:PGSERVICE = 'production'
$env:PGPASSFILE = 'must-not-be-read'
$env:DATABASE_URL = "jdbc:postgresql://$primary/security_db"
$env:SPRING_DATASOURCE_URL = $env:DATABASE_URL
$env:JAVA_TOOL_OPTIONS = '-Dspring.profiles.active=production'
$env:SSL_CERT_FILE = 'must-not-be-read'
$env:PGSSLROOTCERT = 'must-not-be-read'

& $launcher @common -ValidateEndpointsOnly | Out-Null
Assert-Rejected @{PrimaryHost=$primary; StagingHost=$primary; ExpectedStagingEndpointId='ep-production'; ValidateEndpointsOnly=$true} 'distinto de Primary'
Assert-Rejected @{PrimaryHost='ep-production-pooler.example.aws.neon.tech'; StagingHost=$primary; ExpectedStagingEndpointId='ep-production'} 'distinto de Primary'
Assert-Rejected @{PrimaryHost=$primary; StagingHost='ep-staging-pooler.example.aws.neon.tech'; ExpectedStagingEndpointId='ep-staging-pooler'} 'endpoint directo'
Assert-Rejected ($common + @{Database='security_db'; StartBackend=$true}) 'solo están autorizados en castillo_staging'
Assert-Rejected ($common + @{Database='security_db'; ProvisionDatabaseAs='admin_staging'}) 'solo están autorizados en castillo_staging'
Assert-Rejected ($common + @{FlywayTarget='53'; StartBackend=$true; SeedLegacyMfa=$true}) 'requiere un arranque separado'
$withoutCa = $common.Clone()
$withoutCa.Remove('CaBundlePath')
Assert-Rejected $withoutCa 'Indica -CaBundlePath'
Assert-Rejected ($withoutCa + @{CaBundlePath=(Join-Path $PSScriptRoot 'missing-neon-ca.pem')}) 'archivo PEM'
Assert-Rejected ($withoutCa + @{CaBundlePath=$PSCommandPath}) 'archivo PEM'
Assert-Rejected ($withoutCa + @{CaBundlePath=$PSScriptRoot}) 'archivo PEM'
if ($CaBundlePath) {
    Assert-Rejected ($common + @{RestoreBaseline=$PSCommandPath}) 'no coincide con la baseline'
}
if ($global:CmcNeonGuardTestState.PromptCount -or $global:CmcNeonGuardTestState.ConnectionCount) {
    throw 'Unsafe inputs reached the credential prompt or connection.'
}

if ($CaBundlePath) {
    $state = $global:CmcNeonGuardTestState
    $state.Provision.canSetMigrator = $false
    $result = & $launcher @common -ProvisionDatabaseAs 'admin_staging' -CreateMigrator
    if ($result -notcontains 'Base castillo_staging creada.' -or
        $state.PromptCount -ne 1 -or $state.ConnectionCount -ne 3 -or
        $state.GrantCount -ne 1 -or $state.CreatedbCount -ne 1 -or $env:PGPASSWORD) {
        throw 'Unexpected prompt count, connection count or remaining password.'
    }
    $result = & $launcher @common -ProvisionDatabaseAs 'admin_staging' -CreateMigrator
    if ($result -notcontains 'Base castillo_staging existente con el propietario esperado.' -or
        $state.CreatedbCount -ne 1 -or $state.PromptCount -ne 2) {
        throw 'Existing database and role were not reused.'
    }
    $state.Provision.databaseOwner = 'unexpected_owner'
    Assert-Rejected ($common + @{ProvisionDatabaseAs='admin_staging'; CreateMigrator=$true}) 'propietario inesperado'
    $state.Provision.databaseOwner = $null
    $state.Provision.roleIsLimited = $false
    Assert-Rejected ($common + @{ProvisionDatabaseAs='admin_staging'; CreateMigrator=$true}) 'privilegios inesperados'
    $state.Provision.roleExists = $false
    Assert-Rejected ($common + @{ProvisionDatabaseAs='admin_staging'}) 'Falta el migrador'
    if ($state.CreatedbCount -ne 1 -or $state.GrantCount -ne 1 -or $state.PromptCount -ne 5 -or $env:PGPASSWORD) {
        throw 'An invalid partial state attempted a write or an unexpected password prompt.'
    }
    'PASS: explicit CA, inherited environment isolation, masked input and password cleanup.'
    'PASS: partial provisioning states and existing objects.'
}
Remove-Variable -Name CmcNeonGuardTestState -Scope Global
'PASS: endpoint and invalid CA guards.'
