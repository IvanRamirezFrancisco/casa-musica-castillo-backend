# Recuperación del PostgreSQL local original

Estado preparado el 20 de noviembre de 2026. **No se han ejecutado migraciones ni modificaciones persistentes en la base original. Se requiere la autorización solicitada por el propietario después de revisar este documento.**

## Identidad comprobada mediante consultas de solo lectura

| Dato | Valor |
|---|---|
| Servidor | PostgreSQL 18.1 local, `127.0.0.1:5432` |
| Base original | `security_db`, OID `33230` |
| Usuario de aplicación | `spring_app`, sin superusuario |
| Propietario de las tablas que deben alterarse | `postgres`; `spring_app` no pertenece a ese rol |
| Esquema de Flyway | `public.flyway_schema_history` |
| Esquemas de aplicación existentes | `public,auth,security,catalog,sales,customer,ops,analytics` |
| Search path del pool | `public,auth,security,catalog,sales,customer,ops,analytics` |
| Datos encontrados | 9 usuarios, 113 productos, 10 categorías, 1.020 pedidos y 18 sesiones históricas |
| Tablas inventariadas | 65, incluida la historia de Flyway |

La conexión se recuperó del perfil privado anterior preservado en `.local-dev/legacy-application-local.yml.private` y se contrastó con el servidor real. No se supuso el nombre de la base. No se consultaron contraseñas de usuarios ni se mostraron claves de conexión.

## Qué se corrigió

La consolidación había preparado un entorno sintético para pruebas y convertido incorrectamente su identidad en requisito obligatorio del perfil `local`. Por eso aparecía `Local requires the prepared isolated cmc_development database`. Esa condición fue retirada.

- `.local-dev/application.properties` ahora conserva la conexión y las claves originales de JWT/recogida, con la identidad autorizada de `security_db`. Está fuera de Git. La configuración sintética anterior se conservó como `application-isolated-before-original-recovery.properties.private`.
- El guard valida host loopback, puerto, nombre, usuario y OID reales, sin exigir el nombre `cmc_development` ni escribir comentarios/marcadores en la base. Rechaza destinos externos, credenciales administrativas en el arranque de aplicación y conexiones que no coincidan con la identidad aprobada.
- El perfil `local` utiliza solo `classpath:db/migration`: se retiró el baseline reconstruido de pruebas. `ddl-auto=none`, `baseline-on-migrate=false` y `clean-disabled=true`.
- La aplicación **solo valida un esquema ya actualizado**. Si hay migraciones pendientes, se detiene antes de Hibernate, tareas de negocio o DDL. No se permite que `spring_app` intente alterarlas ni se lo convierte en superusuario.
- `prepare-local.py` se transformó en preflight de solo lectura. Ya no crea bases/clústeres, no inicia instancias duplicadas ni ofrece reset/stop de bases.
- `app.maintenance.enabled=false` pausa únicamente en el perfil local las cancelaciones/purgas automáticas y la recuperación automática de registros de backup. El valor por defecto en los servicios sigue siendo `true`, conservando el comportamiento de Production. La validación de expiración/autoridad en cada petición y el despacho de correos de activación/contraseña permanecen activos.
- Se conserva SMTP de Mailpit `127.0.0.1:1025` y su interfaz 8025, sin claves externas efectivas. Se corrigió además la confusión entre la etiqueta ambiental heredada `EMAIL_PROVIDER=brevo` y el transporte local efectivo, que es SMTP. Las claves API no vacías y destinos SMTP externos siguen rechazándose. Production y Brevo no cambiaron.

El proceso que aún apuntaba a la base sintética fue detenido de forma identificada: aplicación PID 21776 y Maven PID 27220. PostgreSQL original, la base aislada y Angular se conservaron. Mientras falte autorización/migración, **8080 no presenta un backend original funcional**; no se mantiene la ejecución sintética como sustituto.

## Respaldo previo, íntegro y privado

Carpeta nueva, sin sobrescribir respaldos anteriores:

`C:\Users\ivanf\Documents\Respaldos privados Casa Musica\postgresql-original-20261120-105335`

- `security_db-before-local-recovery.dump`: pg_dump completo en formato custom, **527.023 bytes**; datos, esquema, objetos y ACL de la base original.
- SHA-256: **`0d08f99bb338ddcbb64c92c42863264455a395c6527e39f474ec267e044e3062`**.
- `pg_restore --list`: 909 entradas; se comprobó presencia de usuarios, catálogo, pedidos e historial Flyway.
- Se procesó el archivo completo con `pg_restore -f NUL`, sin conectarlo a ninguna base: verificación de lectura/descompresión y generación SQL correcta. No se afirma una restauración sobre otra base; no se creó ningún destino de prueba.
- Fingerprints SHA-256 de todas las filas y las columnas originales de las 65 tablas, almacenados en `data-fingerprints-before.private.json`. El plan compara las 64 tablas de datos; el historial Flyway tiene una comprobación separada porque cambiará legítimamente al migrar.
- `backend-before-local-recovery.bundle`: recuperable, verificado con `git bundle verify`; SHA-256 `6997b8614209819e80e7ebacf8485cf130562ff93a054f44997b577ddcd2b874`.
- `private-config-and-state.zip`: perfil anterior, configuración actual previa al cambio, inventario, fingerprints y copias del código de configuración. No publicar esta carpeta; contiene configuración privada. ACL limitada al usuario y SYSTEM.

Comprobar integridad (sin escribir en PostgreSQL):

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\Users\ivanf\Documents\Respaldos privados Casa Musica\postgresql-original-20261120-105335\security_db-before-local-recovery.dump'
& 'C:\Program Files\PostgreSQL\18\bin\pg_restore.exe' --list 'C:\Users\ivanf\Documents\Respaldos privados Casa Musica\postgresql-original-20261120-105335\security_db-before-local-recovery.dump'
```

Restauración de contingencia: requiere autorización separada y un destino vacío explícitamente designado; no ejecutar sobre la base existente ni sobre producción. Con los roles/propietarios y permisos revisados, el comando de restauración es `pg_restore --exit-on-error --single-transaction -h 127.0.0.1 -p 5432 -U postgres -W -d <DESTINO_VACIO_AUTORIZADO> <ARCHIVO_DUMP>`. La contraseña se introduce en el prompt. No se proporcionan ni ejecutan comandos de `clean`, DROP o reinicialización. No se creó otra base de desarrollo para ensayar esta restauración.

## Flyway real e impacto que se somete a autorización

Se encontraron 50 registros satisfactorios: baseline V1 y las migraciones V4–V52. **Los checksums aplicados coinciden con los archivos del repositorio.** No hay reparación, repetición ni cambio de migraciones históricas.

| Pendiente | Impacto | Efecto sobre datos existentes |
|---|---|---|
| V53 `record_email_verification` | Añade `auth.users.email_verified_at` y `auth.verification_tokens.recipient_email` | Columnas nuevas nulas; no modifica nombres, correos, contraseñas ni estado de usuarios |
| V54 `add_email_activation_codes` | Añade digest/intentos OTP, `pending_self_registration` e índice | Valores nuevos por defecto; no reemplaza los desafíos ni registra usuarios |
| V55 `record_legal_acceptances_and_deletion_requests` | Crea dos tablas con FK de aceptación legal y solicitudes de eliminación | Tablas nuevas vacías; no ejecuta solicitudes ni borra cuentas |
| V56 `track_account_deletion_outcomes` | Añade estado, fecha y constraint de resultado | La tabla creada por V55 está vacía; no elimina información |
| V57 `public_account_deletion_tokens_and_admin_review` | Crea tokens de solicitud, añade revisión; permite NULL en `orders.user_id` y `coupon_usage.user_id` | No pone ningún valor a NULL ni desvincula pedidos: solo cambia nulabilidad |
| V58 `activation_delivery_and_shared_limits` | Añade entrega y expiración independiente OTP; crea límites persistentes | Mantiene enlaces existentes; el UPDATE afecta una columna nueva solo donde hay digest. En esta base ese digest se introduce nulo en V54 |
| V59 `password_recovery_delivery` | Sustituye tokens de recuperación por digest, añade estado e índice; crea outbox | Actualmente hay **0 tokens de recuperación**, por lo que ese UPDATE no cambia filas existentes; enlaces futuros conservan el contrato seguro |

No hay DROP TABLE, TRUNCATE ni DELETE en estas siete migraciones. Se revisaron permisos, propietario y disponibilidad de SHA-256 en PostgreSQL 18. Las credenciales de `spring_app` permiten uso de la aplicación, pero no ALTER de las tablas propiedad de `postgres`: ese es el bloqueo administrativo concreto.

## Procedimiento preparado, aún NO ejecutado

La herramienta fija `security_db:5432`, OID 33230, target V59, grupo transaccional PostgreSQL, validación de Flyway y clean deshabilitado. Antes de ejecutar comprueba:

1. Identidad de la base y rol de aplicación; base y claves leídas del archivo privado.
2. SHA-256 del dump y de cada uno de los siete SQL revisados.
3. Checksums de las migraciones ya aplicadas y pendientes exactamente V53–V59.
4. Cantidades y fingerprints de los datos originales. Si cambiaron desde el respaldo, se detiene para revisar/respaldar de nuevo.

La herramienta no crea usuarios de PostgreSQL ni cambia propietarios. Los privilegios predeterminados de `postgres` ya conceden acceso a `spring_app` en los esquemas pertinentes. Tras migrar, el procedimiento limita los permisos **solo de las cinco tablas nuevas** a SELECT/INSERT/UPDATE/DELETE y USAGE de la secuencia nueva del outbox. No altera indiscriminadamente ACL de tablas existentes.

SQL de permisos incluido, también sujeto a la autorización:

```sql
REVOKE ALL PRIVILEGES ON TABLE auth.registration_legal_acceptances,
 auth.account_deletion_requests, auth.account_deletion_tokens,
 auth.activation_request_limits, auth.security_mail_outbox FROM spring_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE auth.registration_legal_acceptances,
 auth.account_deletion_requests, auth.account_deletion_tokens,
 auth.activation_request_limits, auth.security_mail_outbox TO spring_app;
REVOKE ALL PRIVILEGES ON SEQUENCE auth.security_mail_outbox_id_seq FROM spring_app;
GRANT USAGE ON SEQUENCE auth.security_mail_outbox_id_seq TO spring_app;
```

Mostrar el plan, sin migrar ni pedir contraseña administrativa:

```powershell
cd 'C:\Users\ivanf\Documents\7mo\Proyecto del login original y funcional\segundo proyecto'
pwsh -NoProfile -File .\scripts\Invoke-LocalMigrations.ps1
```

**Solo después de la autorización expresa del propietario**, ejecutar:

```powershell
pwsh -NoProfile -File .\scripts\Invoke-LocalMigrations.ps1 -Apply
```

El procedimiento vuelve a mostrar/verificar el plan, exige escribir `APLICAR security_db V53-V59` y después pide la contraseña de `postgres` local con `Read-Host -AsSecureString`. No compartirla en el chat. La contraseña se pasa únicamente al proceso Java mediante una variable temporal, se elimina al terminar y no se guarda en Git, configuración, historial de comandos ni logs. No hay `-ExecutionPolicy Bypass`.

Después de migrar, vuelve a ejecutar validate y compara los fingerprints originales. Las siete migraciones se agrupan en una transacción; los permisos de las tablas nuevas se aplican en otra transacción después. Si hay un fallo, no se restaura ni se fuerza automáticamente: se inspecciona la historia real antes de reintentar. El informe solo se genera al confirmar esquema y datos.

## Arranque y comprobaciones posteriores

El comando habitual seguirá siendo:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

Hasta aplicar las migraciones, el bloqueo esperado es `Local database has pending migrations: 53, 54, 55, 56, 57, 58, 59`. No se habilita Hibernate con un esquema incompatible ni se omiten las validaciones para mostrar 8080 disponible.

La pausa de mantenimiento ya está preparada para impedir que el primer arranque cancele pedidos, purgue sesiones/desafíos/invitaciones o cambie registros históricos de backup. No altera la validez de JWT, MFA, contraseñas o límites en cada solicitud, ni detiene el worker de entrega. Esta pausa local deberá revisarse de forma explícita al habilitar mantenimiento sobre estos datos; nunca cambia silenciosamente los flags almacenados en PostgreSQL. No se ha iniciado mantenimiento sobre la base original.

Después de la autorización/migración se comprobarán catálogo y categorías locales, Login, Perfil, capacidades de seguridad, registro, MFA, favoritos, carrito, recuperación y cambio de contraseña con Mailpit. No se cambiarán contraseñas ni datos de los usuarios históricos para probarlo. Estas pruebas funcionales **siguen pendientes** y no se acreditan con resultados obtenidos anteriormente sobre la base sintética.

El frontend mantiene `npm start -- --configuration development --port 4200`; no se modificó su código en esta corrección. Railway, Production, Google Play y Brevo productivo no se tocaron. `cmc_development` y sus archivos se conservan pendientes de limpieza autorizada futura.

## Verificación realizada y estado Git

- Compilación y empaquetado de la corrección: `BUILD SUCCESS`; 49/49 pruebas unitarias seleccionadas, 0 fallos, 0 errores, 0 omitidas. Incluyen configuración/identidad, prohibición de DDL en el arranque, pausa de mantenimiento, JWT, correo HTML/transporte simulado, expiración de pedidos y scheduler. No se presenta esta tanda como la suite completa ni como una prueba funcional de PostgreSQL tras migrar.
- El plan con PostgreSQL real original pasó. Los 64 fingerprints de datos permanecen iguales. La historia sigue en V52 y todas las migraciones aplicadas conservan sus checksums.
- Se probó el comando habitual: ya no exige `cmc_development`; se detiene correctamente en V53–V59 pendientes, sin DDL ni inicialización de negocio. Las pruebas de catálogo/Login/MFA/carrito/favoritos/Perfil/capacidades/recuperación reales están pendientes de la autorización y actualización del esquema.
- La primera ejecución unitaria bajo el sandbox quedó bloqueada en la instrumentación de mocks; se conservó su log y se repitió con el mecanismo permitido. Un intento de regresión coincidió con otra compilación sobre `target`; se repitió secuencialmente y pasó. No se borraron pruebas ni se redujeron aserciones. Los avisos JaCoCo por datos históricos acumulados no acreditan cobertura de esta corrección.
- Rama backend `fix/account-activation-hardening`, HEAD base `72c817f6efa6b18c446ce369dde3160373d80e03`. Cambios nuevos sin commit para revisión. No se crearon ramas, clones ni worktrees; no hubo push, merge o despliegue.
- Archivos de configuración/procedimiento: `LocalDevelopmentConfiguration.java`, `application-local.properties`, `prepare-local.py`, `Invoke-LocalMigrations.ps1`, `LocalDatabaseMigrations.java`, `AGENTS.md`, este informe y la advertencia en `LOCAL_CONSOLIDATION.md`.
- Pausa local de mantenimiento, con defaults de Production conservados: `SessionManagementService`, `OrderExpirationJob`, `DynamicSchedulerService`, `ActivationJobs`, `LoginChallengeService`, `SecurePasswordResetService`, `StaffInvitationService`, `DatabaseBackupService`. Pruebas: `LocalDevelopmentConfigurationTest` y `LocalMaintenancePolicyTest`.
- Configuración privada y pruebas/evidencia: `.local-dev`, ignorado por Git. Credenciales administrativas no solicitadas en el chat, no guardadas ni usadas. No se ejecutó el modo `-Apply`.
