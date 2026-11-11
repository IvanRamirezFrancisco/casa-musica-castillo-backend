# Run in a child PowerShell process. The launcher deliberately clears inherited environment variables.
$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot 'start-neon-staging.ps1'
$primary = 'ep-production.example.aws.neon.tech'
$staging = 'ep-staging.example.aws.neon.tech'
$common = @{
    PrimaryHost = $primary
    StagingHost = $staging
    ExpectedStagingEndpointId = 'ep-staging'
}
$global:CmcNeonGuardTestState = @{
    PromptCount = 0
    ConnectionCount = 0
    Password = [guid]::NewGuid().ToString('N')
    Staging = $staging
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
    if ($env:PGHOST -cne $global:CmcNeonGuardTestState.Staging -or $env:PGDATABASE -cne 'postgres' -or
        $env:PGUSER -cne 'admin_staging' -or $env:PGSSLMODE -cne 'verify-full' -or
        $env:PGSSLROOTCERT -cne 'system' -or $env:PGOPTIONS -cne '-c default_transaction_read_only=on' -or
        $env:SPRING_DATASOURCE_URL -or $env:DATABASE_URL -or $env:PGSERVICE -or $env:PGPASSFILE -cne 'NUL' -or
        $env:JAVA_TOOL_OPTIONS -or $env:PGPASSWORD -cne $syntheticPassword -or
        ($args -join ' ').Contains($syntheticPassword)) {
        throw 'The launcher did not isolate the connection or supplied a password as an argument.'
    }
    $global:LASTEXITCODE = 0
    'f' # Simulate a refusal by the read-only SQL precondition. No database is contacted.
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

& $launcher @common -ValidateEndpointsOnly | Out-Null
Assert-Rejected @{PrimaryHost=$primary; StagingHost=$primary; ExpectedStagingEndpointId='ep-production'; ValidateEndpointsOnly=$true} 'distinto de Primary'
Assert-Rejected @{PrimaryHost='ep-production-pooler.example.aws.neon.tech'; StagingHost=$primary; ExpectedStagingEndpointId='ep-production'} 'distinto de Primary'
Assert-Rejected @{PrimaryHost=$primary; StagingHost='ep-staging-pooler.example.aws.neon.tech'; ExpectedStagingEndpointId='ep-staging-pooler'} 'endpoint directo'
Assert-Rejected ($common + @{Database='security_db'; StartBackend=$true}) 'solo están autorizados en castillo_staging'
Assert-Rejected ($common + @{Database='security_db'; ProvisionDatabaseAs='admin_staging'}) 'solo están autorizados en castillo_staging'
Assert-Rejected ($common + @{RestoreBaseline=$PSCommandPath}) 'no coincide con la baseline'
Assert-Rejected ($common + @{FlywayTarget='53'; StartBackend=$true; SeedLegacyMfa=$true}) 'requiere un arranque separado'
if ($global:CmcNeonGuardTestState.PromptCount -or $global:CmcNeonGuardTestState.ConnectionCount) {
    throw 'Unsafe inputs reached the credential prompt or connection.'
}

Assert-Rejected ($common + @{ProvisionDatabaseAs='admin_staging'}) 'rol administrador de staging con CREATEDB'
if ($global:CmcNeonGuardTestState.PromptCount -ne 1 -or $global:CmcNeonGuardTestState.ConnectionCount -ne 1 -or $env:PGPASSWORD) {
    throw 'Unexpected prompt count, connection count or remaining password.'
}
Remove-Variable -Name CmcNeonGuardTestState -Scope Global
'PASS: endpoint guards, inherited environment isolation, masked input and password cleanup.'
