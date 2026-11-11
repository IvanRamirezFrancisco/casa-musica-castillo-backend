# Ensayo Neon V50→V52

Ejecutar en Windows PowerShell interactivo sin transcripción, desde el worktree backend. En Neon Console cotejar los ID de rama y los ID de cómputo en **Branches → Connect**, con pooling desactivado en staging. Usar únicamente el administrador del endpoint directo de `staging-auth-mfa-v52`. Los hostnames e identificadores se leen localmente. No guardar contraseñas en variables de usuario, archivos ni argumentos.

```powershell
$primary = Read-Host 'Hostname directo de production confirmado en Neon Console'
$stage = Read-Host 'Hostname directo de staging-auth-mfa-v52 confirmado en Neon Console'
$endpoint = Read-Host 'Endpoint ID de staging-auth-mfa-v52 confirmado en Neon Console'
$common = @('-PrimaryHost', $primary, '-StagingHost', $stage, '-ExpectedStagingEndpointId', $endpoint, '-Database', 'castillo_staging')
$launcher = (Resolve-Path 'scripts/start-neon-staging.ps1').Path
powershell -NoProfile -File $launcher @common -ValidateEndpointsOnly
```

Si el migrador todavía no existe, crear el rol y la base en un solo paso de operador. Si el rol limitado ya existe, omitir `-CreateMigrator`. Si la base ya existe, omitir el paso de aprovisionamiento y pasar a la inspección o restauración de la base vacía.

```powershell
$admin = Read-Host 'Rol administrador del endpoint de staging'
powershell -NoProfile -File $launcher @common -ProvisionDatabaseAs $admin -CreateMigrator
```

El lanzador pide la contraseña del administrador de staging y, si crea el rol, `psql \password` pide la nueva contraseña del migrador dos veces sin mostrarla. El administrador necesita `CREATEDB` y, para crear el rol, `CREATEROLE`. PostgreSQL comprueba las facultades para asignar la propiedad al migrador. El lanzador nunca cambia `security_db`.

Restaurar desde la ruta `dumpPath` registrada en el informe `v50-backup.json`. Abrir una sesión privada nueva con el migrador y guardar solo la salida saneada.

```powershell
$dump = Read-Host 'Ruta local al dump sintético V50 verificado'
$evidence = (New-Item -ItemType Directory -Force -Path 'target/neon-cloud-evidence').FullName
powershell -NoProfile -File $launcher @common -RestoreBaseline $dump |
    Tee-Object -FilePath (Join-Path $evidence 'baseline-v50.txt')
```

Sembrar la cuenta sintética TOTP en V50. El lanzador genera el secreto y una contraseña aleatoria dentro de PostgreSQL. La evidencia contiene únicamente una huella de la fila para cotejarla tras V52.

```powershell
powershell -NoProfile -File $launcher @common -SeedLegacyMfa |
    Tee-Object -FilePath (Join-Path $evidence 'fixture-v50.txt')
```

Con Mailpit en `127.0.0.1:11025` y su interfaz en `127.0.0.1:18025`, iniciar el JAR exacto V52 preservado en `target/neon-v52`. Para el ensayo local de clientes, la web y el gateway usan el mismo origen HTTPS configurado en la variable siguiente. Mantener la sesión activa durante la prueba.

```powershell
$webOrigin = 'https://localhost:18443'
powershell -NoProfile -File $launcher @common -WebOrigin $webOrigin -StartBackend
```

Desde otra terminal, con los parámetros comunes, ejecutar la inspección de solo lectura después de que Spring esté saludable. El historial esperado termina con V51 `-1106382170` y V52 `1411222195` y conserva el digest del historial V50. Comparar también `legacyMfaFixtureSha256` con `fixture-v50.txt`. La comprobación sin `-StartBackend` no migra ni restaura.

```powershell
powershell -NoProfile -File $launcher @common |
    Tee-Object -FilePath (Join-Path $evidence 'flyway-v52.txt')
```

La huella acredita preservación de la fila sintética. Para probar login TOTP y rechazo de reutilización, enrolar otra cuenta sintética a través de la API y completar el desafío. La suite local `Schema50UpgradeTest` comprueba además la preservación de un enrolamiento previo en PostgreSQL aislado.

Cargar catálogo después de V52 con `-SeedCommerceFixtures`. Tras registrar una cuenta de prueba usando el generador existente (usuario `stg-<sufijo>` y correo sintético `castillo.lab.<sufijo>@gmail.com`), repetir con `-FixtureUsername` para generar un pedido propio de prueba. Mailpit debe seguir siendo el único receptor del ensayo. No usar checkout para preparar pedidos de aceptación.

```powershell
powershell -NoProfile -File $launcher @common -SeedCommerceFixtures
```
