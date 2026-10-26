# Auth Hardening — Casa de Música Castillo

Fecha de validación: 2026-10-25. Rama: `security/auth-hardening`.

**Frontend dependency remediation is a deployment blocker.** Este cambio no autoriza despliegue. Las dependencias HIGH/CRITICAL del frontend se remediarán en una rama separada antes de desplegar.

## Estado inicial y alcance

Backend HEAD `2d2a0bc`, rama requerida y working tree inicialmente limpio. Frontend HEAD `353acd2`, rama `security/auth-hardening-web`, inicialmente limpio. Se ejecutaron branch/status/log/stash list. Los stashes de application-test, Actividad 6 PWA y UI/UX no se abrieron, aplicaron ni modificaron. No se modificó móvil, PWA, dependencias, producción, variables de proveedores ni sesiones reales. No commit/push/PR/deploy.

## Arquitectura anterior y hallazgos confirmados

- AuthController/JwtTokenProvider emitían HS512, pero coexistían generadores sin sesión y un refresh basado en el access token.
- SecureAuthController/SecureJwtService usaban HS256, `sub=email`, conversión de principal a User, sin `jti`, cookies y mapas de refresh/blacklist por proceso. No eran compatibles con el filtro oficial ni aplicaban el flujo oficial de 2FA.
- `/api/2fa/verify` aceptaba email+código sin acreditar contraseña previa; send-login-code podía activar permanentemente EMAIL.
- Filtro y SecurityConfig excluían/permitían todo `/api/auth/**`. `/me` reinterpretaba el header sin comprobar toda la política de sesión.
- Errores de validación y mensajes de excepciones podían contener valores rechazados o detalles internos.

Se buscaron consumidores en código, servicios, componentes, interceptors, tests, scripts y documentación de ambos repositorios. El consumidor funcional de 2FA es LoginComponent/AuthService. No se encontraron consumidores de secure-login, secure-logout ni refresh. ANALISIS_COMPLETO.md del frontend menciona refresh como endpoint sin llamadas. JobRunnableFactory sí dependía de la limpieza del servicio alternativo: se migró a la limpieza de sesiones persistentes antes de eliminarlo. Esto es evidencia de los repositorios locales, no telemetría de consumidores externos.

## Arquitectura oficial final

`AuthController → AuthenticationManager → UserPrincipal → [LoginChallengeService si 2FA] → JwtTokenProvider → SessionManagementService → auth.active_sessions`.

Los recursos protegidos reciben únicamente `Authorization: Bearer <access JWT>`. JwtAuthenticationFilter valida firma/expiración/algoritmo, exige `jti`, consulta sesión válida, comprueba ownership con `sub`, carga usuario actual y authorities actuales, comprueba habilitación/bloqueo/expiraciones y actualiza actividad. No acepta challenge ni cookies como autenticación normal.

### JWT y sesiones

- HS512, `sub` numérico = ID usuario; `jti` = UUID persistido; claims email, roles/authorities, iat y exp.
- Hay un solo generador de access JWT: `generateToken(Authentication, HttpServletRequest)`. Falla si no obtiene sesión. No quedan generadores oficiales sin `jti` ni generación HS256.
- Se preserva el tiempo de vida del generador oficial: `app.security.jwt.expiration`, con el default existente de 86400000 ms. Se eliminan access-token-expiration y refresh-token-expiration de app.security.jwt por carecer de consumidores; expiration se declara explícitamente a 86400000. Producción, que ya definía el TTL correcto, no cambia. Las propiedades homónimas de app.oauth2 conservan consumidores legítimos en SecureOAuth2Service y no son JWT oficiales.
- Sesión existente, no revocada, no vencida y no inactiva. Inactividad por defecto: 15 minutos. Máximo por defecto: 3 sesiones, configurable con valor positivo.
- Se bloquea la fila del usuario al crear sesiones, serializando emisiones concurrentes y revocando las más antiguas al superar el límite. La revocación persiste en BD.
- `/me` usa el principal validado y obtiene su DTO dentro del servicio transaccional; no decodifica otro token. No devuelve password.
- Logout revoca el `authenticatedJti` establecido por el filtro. Logout-all usa Authentication.getName(); ignora IDs arbitrarios del body. La revocación por ID verifica ownership dentro del servicio; inexistente/ajena devuelve el mismo 404. Revocación idempotente en BD; reutilizar un JWT ya revocado recibe 401.

### Rutas públicas explícitas

PublicAuthRoutes es compartido por SecurityConfig y el filtro; no hay exclusión genérica de auth. Los mappings del controller conservan sus métodos HTTP.

| Ruta                                                                                            | Propósito                                                           |
| ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| `/api/auth/login`, `/register`                                                                  | Credenciales / registro                                             |
| `/api/auth/verify-email`, `/verify`, `/resend-verification`                                     | Verificación de cuenta                                              |
| `/api/auth/forgot-password`, `/validate-reset-token`, `/reset-password`                         | Recuperación existente usada por web                                |
| `/api/auth/password-reset/request`, `/password-reset/confirm`, `/password-reset/verify/{token}` | Contrato alternativo de recuperación existente; no emite access JWT |
| `/api/auth/check-username/{username}`                                                           | Disponibilidad existente                                            |
| `/api/auth/accept-invitation/{token}`, `/accept-invitation/validate/{token}`                    | Invitación de staff existente                                       |
| `/api/2fa/send-login-code`, `/api/2fa/verify`                                                   | Preautenticación ligada al challenge                                |

Los paths abreviados en la primera columna comparten `/api/auth`. El resto de auth requiere autenticación, incluidos me/logout/logout-all/sessions/revocación. Enrollment/configuración de 2FA sigue protegido. Se conservan las reglas de catálogo, APIs públicas, RBAC, pedidos, Mercado Pago y Alexa fuera de auth.

### Endpoints/código retirados

- Eliminados SecureAuthController y SecureJwtService.
- Sin handlers: secure-login, secure-logout, refresh, refresh-token, csrf-token y session-status del controller alternativo.
- Retirados AuthService.authenticateUser/refreshToken/getUserFromToken obsoletos y los generadores sin sesión.
- Mantenimiento legítimo: JobRunnableFactory ahora limpia sesiones persistentes con SessionManagementService; no queda un adaptador vacío.
- Entidad/repositorio históricos RefreshToken no se integran ni exponen; se preservan tablas/datos existentes. No se borra información para este hardening.

**El contrato oficial actual no incluye renovación automática de access tokens.** Tras expiración/revocación se inicia login nuevamente. No hay refresh nuevo ni credenciales de renovación en memoria.

## Challenge de preautenticación

Solo se crea después de AuthenticationManager y de comprobar la cuenta. Respuesta pendiente:

```json
{
  "success": true,
  "message": "Two-factor authentication required",
  "data": {
    "twoFactorRequired": true,
    "preAuthChallenge": "<opaque-token>",
    "availableMethods": {
      "EMAIL": true,
      "GOOGLE_AUTHENTICATOR": false,
      "BACKUP_CODE": false
    }
  }
}
```

No incluye access JWT ni datos personales del usuario. El challenge no es JWT ni autentica recursos normales.

- SecureRandom, 32 bytes (256 bits), Base64URL sin padding: 43 caracteres.
- BD conserva exclusivamente SHA-256 del token aleatorio; no token original.
- Asociado por FK a usuario y propósito `LOGIN_2FA`, TTL 5 minutos.
- Nuevo login correcto sustituye el challenge pendiente anterior. Se elimina el hash EMAIL de challenges consumidos/sustituidos.
- Cinco intentos por challenge; agotado/expirado/consumido/corrupto/inexistente no autentica.
- Presupuesto persistente por usuario y ventana de 15 minutos: hasta 5 challenges, 3 envíos EMAIL, 5 verificaciones. Sustituir challenge/reiniciar instancia no reinicia este presupuesto. Create/send también rechazan con 429 cuando attempts ya está agotado: no crean challenges ni envían códigos que no podrían verificarse.
- Lock de usuario antes de lock del challenge; misma transacción para validación, intentos, consumo, verificación del factor, sesión y emisión JWT. Errores controlados de autenticación confirman contadores; fallos internos revierten consumo/sesión. Dos consumidores concurrentes no emiten dos JWT.
- Limpieza horaria de challenges cuyo vencimiento tiene más de un día; limpieza oportunista de expirados del usuario al crear. Limits tiene una fila por usuario y FK con cascade.

### Contratos de envío y verificación

```json
{ "preAuthChallenge": "<opaque-token>", "method": "EMAIL" }
```

```json
{
  "preAuthChallenge": "<opaque-token>",
  "method": "EMAIL",
  "code": "<user-input>"
}
```

El usuario se resuelve desde la huella del challenge, nunca desde email/userId aportado por el navegador. Challenge es obligatorio. Errores de input 400, autenticación 401, autorización 403, cuotas 429, fallos internos 5xx saneados.

### Factores

- EMAIL: solo habilitado previamente. El envío de login no activa preferencias. Código de seis dígitos SecureRandom; hash BCrypt persistido por challenge, nunca respuesta/log. El último envío reemplaza el anterior y comparte vencimiento del challenge. Hash y cuota se confirman antes de llamar al proveedor fuera de la transacción de preparación; un fallo/timeout devuelve 503 sin devolver cuota ni invalidar automáticamente el código.
- TOTP: se conserva la librería GoogleAuthenticator y su ventana existente de 30 s con tolerancia hasta ±2 slots; no se persisten códigos temporales. Se exige método habilitado y challenge. Secret/QR solo durante enrollment pendiente protegido; después de confirmarlo, qrcode/manual-code devuelven 403 y no existe recuperación del secreto.
- Backup: BCrypt existente, asociado al usuario, un uso. Se añade locking de filas no usadas para impedir reutilización concurrente entre solicitudes. El login también exige challenge y un método principal habilitado.
- Tras completar 2FA se limpian los contadores de intentos fallidos para la identidad resuelta por challenge y la IP del request; no se eliminan bloqueos vigentes ni cuotas persistentes de factor.
- Los mapas EMAIL legacy de TwoFactorService quedan limitados a operaciones autenticadas de configuración existentes; ya no son fuente de credenciales del login.

## Secrets, errores y logging

Producción exige la variable JWT requerida por su perfil, sin generar un secreto nuevo al arrancar. JwtTokenProvider falla con menos de 64 bytes UTF-8. La entropía debe venir de aprovisionamiento criptográficamente aleatorio; medir longitud no demuestra aleatoriedad. No se leyó ni rotó el valor productivo. Si un secreto de origen inseguro se usó anteriormente, la rotación requiere un procedimiento separado: planificar impacto en todos los JWT, aprovisionar fuera de Git, coordinar reinicio y reautenticación; no ejecutarlo automáticamente.

AuthExceptionHandler devuelve mensajes controlados, tipo de excepción y referencia técnica sin mensaje/stack/valores. Bean Validation no registra la excepción con rejected values. Controllers y servicios auth/2FA saneados no vuelcan password, JWT, Authorization, challenge, códigos ni secreto TOTP. Los logs locales de pruebas no forman parte de los cambios versionados. Fixtures de tests son sintéticos.

## Flyway y pruebas reproducibles

V51 añade solamente `auth.login_challenges` y `auth.login_factor_limits`: identity PK, SHA-256 único, FK auth.users ON DELETE CASCADE, purpose check, contadores acotados/no negativos, timestamps y checks temporales, índices por usuario y expiración. Es una migración versionada de ejecución única: Flyway aporta idempotencia mediante historial/checksum; una segunda migrate no recrea tablas. No pretende ser un script manual repetible.

### Deuda histórica de inicialización resuelta para tests

La rama base contiene V4..V50, sin V1–V3 y sin CREATE inicial de products/users. `git diff deploy/railway-vercel-alexa...security/auth-hardening -- src/main/resources/db/migration src/test pom.xml src/main/resources/application.yml` no muestra diferencias previas al trabajo. La configuración de esa base era ddl-auto=none; el historial anterior al primer V4 usaba Hibernate update. Una BD vacía falla determinísticamente en V4, antes de cualquier cambio auth.

Se añadió **solo a test resources** un baseline completo `db/test-baseline/V1__legacy_test_schema.sql`, exportado de entidades/enums versionados en `ce73f95` con Hibernate 6.3.1 y naming Spring. Contiene tablas/columnas/PK/FK/checks completos. Se documentan tres columnas legacy que V5/V6 convierten pero ya no estaban en el modelo Java, y la tabla externa a JPA inventory_prediction_config a partir de su contrato JDBC/V13/V23. No son placeholders vacíos para evitar V4. La fila/rol NOLOGIN spring_app reproduce el prerrequisito de V11 en el clúster desechable.

Se ejecutaron desde cero V1 de tests y **todas** las migraciones V4..V52: 50 migraciones, historial validado. No se modificó, saltó ni desactivó ninguna migración histórica. La ubicación test no se empaqueta ni se configura en producción. LoginChallengeServiceTest usa también este esquema completo, no una auth.users ficticia.

```powershell
./scripts/Start-AuthTestPostgres.ps1
mvn test
mvn verify -Ddependency-check.skip=true
./scripts/Start-AuthTestPostgres.ps1 -Stop
```

PostgreSQL temporal en 127.0.0.1:55439, usuario y dos bases sintéticas. LocalPostgresIntegrationTest impone propiedades con mayor prioridad que variables ambientales. El test de challenges solo limpia su base dedicada fija; no puede seleccionar producción por entorno. Se conserva el stash application-test; el perfil versionado se actualizó directamente con valores sintéticos.

Cobertura: login correcto/incorrecto/no verificado/usuario inexistente/rate limit; challenge y cuotas; hashes/256 bits/TTL/purpose/FK; expirado/usado/corrupto/intentos; EMAIL/TOTP/backup; concurrencia PostgreSQL y rollback al fallar sesión; firma/HS512/exp/jti/sesión/usuario; filtro y rutas; /me; logout/all/ownership/máximo/inactividad; errores sin datos sensibles. Integración HTTP usa servicios reales de credenciales/JWT/sesiones, y mock de transporte EMAIL para no enviar correos.

Resultados finales y estado Git se registran al final de este documento. No se eliminaron/deshabilitaron tests ni se redujo seguridad para obtener verde.

## Limitaciones y compatibilidad

- Password/IP rate limiting heredado de LoginSecurityService sigue teniendo estado por proceso; las cuotas de challenge/factor sí son persistentes. Recuperación conserva sus controles existentes. No se afirma protección global multiinstancia para todos los mecanismos legacy.
- Timestamps siguen las convenciones LocalDateTime del backend: desplegar instancias con la misma zona horaria. El transporte EMAIL sucede después de confirmar hash/cuota; un timeout puede significar que el proveedor ya aceptó el mensaje. No hay devolución automática de cuota ni bucle de reenvío en el coordinador; todo reintento del usuario consume el presupuesto persistente. El último hash permanece verificable hasta reemplazo/expiración/consumo.
- JWT HS512 existentes con jti y sesión activa siguen válidos con el mismo secreto. No se invalidan masivamente sesiones. JWT sin jti o del esquema alternativo se rechazan.
- Web nuevo y backend nuevo requieren rollout coordinado para usuarios 2FA. Móvil no se modificó; TT-03 sin 2FA conserva contrato de login; un futuro cliente 2FA deberá implementar challenge.
- Frontend dependency remediation is a deployment blocker. La suite global frontend conserva fallos previos demostrados en base; no se presenta como validación global verde.

## Rollout y rollback — solo plan, no ejecutado

1. Remediar HIGH/CRITICAL de frontend y resolver deuda de pruebas globales en tareas separadas. Revisar configuración de secreto y zona horaria sin exponer valores.
2. En staging aislado, aplicar V51 y V52 por Flyway con credenciales de migración apropiadas; probar el par backend/frontend y recovery/RBAC/sesiones con usuarios sintéticos.
3. Coordinar publicación de ambos artefactos y actualización de clientes/caché según el proceso existente. Usuarios que estaban en mitad de 2FA reinician login; usuarios con sesiones válidas no requieren logout general.
4. Confirmar métricas saneadas de 400/401/429/5xx y fallos de envío, sin tokens/challenges en observabilidad.
5. Si falla el cambio, mantener tablas aditivas y sesiones; no aplicar DROP ni rotar secretos como rollback. Retirar temporalmente tráfico de login/2FA o corregir hacia delante con un par compatible. Nunca reabrir email+código sin challenge, endpoints debug, recuperación del secreto confirmado ni JWT inseguros; tampoco desactivar la protección de replay para facilitar rollback.

## Correcciones finales de Security Review

### HIGH-01: exposición TOTP cerrada

Se buscaron los dos endpoints debug y sus helpers en ambos repositorios, tests, scripts y documentación. Solo existían las declaraciones productivas y un comentario de desarrollo; no había consumidores legítimos. Se retiraron `/api/2fa/debug/validate-totp` y `/api/2fa/debug/generate-test-code`, así como los helpers que devolvían otpAuthUrl/currentCode/currentValidCode o secretos de debug. SecurityConfig además deniega `/api/2fa/debug/**` en todos los perfiles.

QRCode/manual-code delegan a funciones transaccionales de enrollment con lock del usuario. Si googleAuthEnabled=true, responden 403 sin secreto, QR, manualEntryKey, URL secret= ni código actual. Setup y confirmación usan el mismo lock para serializar la transición; setup no sobreescribe un autenticador confirmado. El enrollment pendiente conserva QR/manualEntryKey necesarios. Desactivar los métodos existentes elimina el secreto; no se crea un mecanismo de recuperación del secreto confirmado. Recuperación/backup/reset administrativo existentes no se amplían.

TotpExposureProductionTest arranca realmente con perfil production y comprueba ausencia de mappings debug, rechazo incluso autenticado y preservación del enrollment pendiente. Los secretos de prueba se generan en runtime; no se versionan valores reales.

### MED-01: TOTP de login de un solo uso

V52\_\_prevent_totp_replay.sql añade exclusivamente `last_verified_totp_slot BIGINT NULL` a auth.login_factor_limits, con check no negativo. No modifica auth.users ni V4–V51. Usuarios existentes empiezan con NULL.

TotpService usa GoogleAuthenticator para determinar el contador que produce el match dentro de la ventana existente ±2 slots (30 s), sin reducirla. Si hay un match ambiguo se elige el slot más alto. LoginChallengeService actualiza condicionalmente el contador solo si es estrictamente mayor que el último aceptado. El update sucede bajo lock del usuario, en la misma transacción que consume challenge/crea sesión/emite JWT. Rollback de un fallo interno revierte ese estado. Las ventanas de cuotas no borran el contador TOTP.

Una prueba con PostgreSQL, validación TOTP y JWT reales provisiona dos challenges independientes y ejecuta dos requests simultáneos con el mismo TOTP: exactamente un 200, un 401 y una sesión. Un segundo login con contraseña tampoco puede repetirlo. Otros tests acreditan rechazo de slots iguales/anteriores y persistencia al cambiar ventana de cuotas. No se persiste código TOTP ni hash del código.

### MED-02: correo externo fuera de lock DB

Send declara Propagation.NEVER: rechaza llamadores con una transacción ambiental, porque suspenderla no liberaría sus locks. No ejecuta transporte dentro de la transacción de preparación. TransactionTemplate utiliza una transacción propia para validar challenge/método/cuotas, generar código y persistir BCrypt+contador; retorna solo un destinatario mínimo y el código temporal, con toString redactado. Después de commit se invoca EmailService. No existe self-invocation de un método @Transactional para esta separación.

Si el proveedor falla, se devuelve 503 controlado y se registra solo el tipo de excepción. El hash y la cuota permanecen confirmados, porque el proveedor puede haber aceptado el mensaje pese al timeout. No se hace refund, revocación del hash ni reenvío ilimitado. Los fallbacks de transporte existentes permanecen acotados y usan el mismo código. El siguiente envío autorizado sustituye el hash anterior y consume otra unidad de cuota.

El test verifica ausencia de transacción durante EmailService y que una segunda conexión obtiene FOR UPDATE NOWAIT sobre el usuario. Otros tests prueban que tres fallos de proveedor consumen las tres cuotas, el cuarto envío recibe 429 y el código no aparece en logs/respuesta.

### MED-04 y LOW-01/02/03

- Tras registrar un fallo de contraseña se comprueba bloqueo tanto por email como por IP mediante isAccountLocked existente; cualquiera activa 429 con mensaje controlado, sin datos de cuenta. Se conserva el rate limiting previo.
- Frontend maneja 429 sin remainingTimeSeconds con texto fijo, sin mostrar detalles internos ni un error inesperado.
- El interceptor compara una lista de claves en lowercase, incluidos accesstoken/refreshtoken/preauthchallenge; el test demuestra que los valores opacos no cambian y el resto sigue saneándose.
- Create y send comprueban attempts disponible bajo lock y respetan el reset de ventana. No se permite generar/enviar algo imposible de verificar por cuota agotada.
- Configuración JWT oficial explícita 86400000 ms; propiedades huérfanas retiradas solo del namespace eliminado. OAuth2 y production se preservan.

### Alcance Global Search y MED-03

Global Search ya no forma parte del diff frontend. Su patch quedó fuera de ambos repositorios en `C:\Users\ivanf\AppData\Local\Temp\auth-hardening-review-hpkepuus\global-search.component.spec.patch`, SHA-256 `7661767ba4b9499ff36d00b6badc73a332c102abc3928912fdccf046d1a45090`. Se verificó git apply --check después de restaurar el archivo exactamente a deploy/vercel-frontend. La suite global vuelve a mostrar los 17 errores TypeScript históricos, sin recuperar el patch para obtener verde.

**MED-03 pendiente:** el frontend sigue limpiando el challenge tras un primer verify fallido. No se cambió esa semántica ni se inventaron error codes nuevos; su mejora de UX se tratará después.

### Dependency Check backend

Se inspeccionó el plugin existente 9.0.7: umbral failBuildOnCVSS=7, formatos HTML/JSON, suppressionFile existente. Se intentó `mvn org.owasp:dependency-check-maven:9.0.7:check -DnvdMaxRetryCount=1 -DconnectionTimeout=10000` sin API key y sin modificar pom.xml/SSL/dependencias.

Resultado: BUILD FAILURE de la consulta externa, NVD HTTP 403 / UpdateException / NoDataException (No documents exist). No se obtuvo un informe utilizable ni un inventario CVE; HIGH/CRITICAL backend son **no determinados por este intento**, no cero. Se requiere repetir el análisis con acceso NVD autorizado o una caché válida en un trabajo posterior. Este fallo no bloqueó los tests funcionales de Auth. El verify solicitado usa dependency-check.skip=true de forma explícita, no se presenta como un escaneo de dependencias aprobado.

Frontend dependency remediation is a deployment blocker. No se actualizaron paquetes ni se cambió el alcance de MED-03.

## Validación y estado Git finales

| Validación final                        | Resultado                                                                                                                                          |
| --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| mvn test                                | **205 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS**                                                                                     |
| mvn verify -Ddependency-check.skip=true | **205 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS**, empaquetado correcto                                                               |
| Suite focal previa de correcciones      | 45 tests, 0 failures/errors/skipped; la suite completa final añade y verifica también el rechazo de transacciones ambientales                      |
| Auth cubierto por suite final           | 57 casos Auth/TOTP/sesiones/logging; ningún test deshabilitado ni assertion reducida para ocultar un fallo                                         |
| Flyway desde limpio                     | 50 migraciones versionadas exitosas (baseline test V1 + V4..V52), última 52; V1/V4/V51/V52 success=true, historial validado                        |
| Segundo estado válido                   | backend_hardening_test también muestra 50 migraciones exitosas y última 52                                                                         |
| Producción / artifact                   | V51/V52 empaquetadas; sin classes JWT alternativo, debug TOTP ni generación de current TOTP; baseline/config test no empaquetados                  |
| Dependency Check backend                | Intentado, BUILD FAILURE externo NVD HTTP 403 / NoDataException; sin reporte ni inventario CVE utilizable; HIGH/CRITICAL no determinados           |
| Diff check / secretos                   | git diff --check correcto; revisión manual de diff y nuevos archivos sin secretos reales; patrones private key/live API key/JWT firmado literal: 0 |
| Dependencias / perfiles                 | pom.xml y application-production.yml sin diff; TTL oficial 86400000 ms conservado                                                                  |

Warnings conservados: Flyway existente advierte soporte probado hasta PostgreSQL 15 mientras la instancia local es 18.1; no se actualizó dependencia. Warnings previos de Mockito/ByteBuddy, commons-logging, Lombok/unchecked; transacción ya abierta en seed histórico y schemas existentes. Git avisa normalización LF/CRLF en dos tests anteriores; diff --check no falla.

Confirmaciones del alcance: sin debug TOTP en production; sin recuperación del secreto confirmado; replay rechazado con slot monotónico y exactamente un JWT en concurrencia real; email externo fuera de transacciones/locks de preparación y rechazo de llamadores con transacción ambiental; 429 considera email e IP; attempts agotado impide create/send; ningún refresh incompatible activo; sin valores reales de password/JWT/challenge/TOTP/backup/API key añadidos al repositorio. Fixtures sintéticos no son credenciales productivas.

Frontend: **30/30 Auth/recovery PASS**; suite global bloqueada por **17 errores TypeScript preexistentes** de Global Search restaurado; build correcto; audit 23 (7 moderate, 15 high, 1 critical). Frontend dependency remediation is a deployment blocker. MED-03 no se implementó.

### git status --short --untracked-files=all

```text
 M src/main/java/com/security/config/SecurityConfig.java
 M src/main/java/com/security/controller/AuthController.java
 M src/main/java/com/security/controller/GlobalExceptionHandler.java
 M src/main/java/com/security/controller/PasswordResetController.java
 D src/main/java/com/security/controller/SecureAuthController.java
 M src/main/java/com/security/controller/SecurePasswordResetController.java
 M src/main/java/com/security/controller/TwoFactorController.java
 M src/main/java/com/security/interceptor/SecurityInterceptor.java
 M src/main/java/com/security/repository/BackupCodeRepository.java
 M src/main/java/com/security/repository/UserRepository.java
 M src/main/java/com/security/security/JwtAuthenticationFilter.java
 M src/main/java/com/security/security/JwtTokenProvider.java
 M src/main/java/com/security/service/AuthService.java
 M src/main/java/com/security/service/BackupCodeService.java
 M src/main/java/com/security/service/LoginSecurityService.java
 M src/main/java/com/security/service/PasswordResetService.java
 D src/main/java/com/security/service/SecureJwtService.java
 M src/main/java/com/security/service/SessionManagementService.java
 M src/main/java/com/security/service/TotpService.java
 M src/main/java/com/security/service/TwoFactorService.java
 M src/main/java/com/security/service/automation/JobRunnableFactory.java
 M src/main/resources/application.yml
 M src/test/java/com/security/controller/OrderOwnershipIntegrationTest.java
 M src/test/java/com/security/controller/admin/AdminDemandForecastControllerWebMvcTest.java
 M src/test/resources/application-test.yml
?? docs/security/AUTH_HARDENING.md
?? scripts/Start-AuthTestPostgres.ps1
?? src/main/java/com/security/controller/AuthExceptionHandler.java
?? src/main/java/com/security/dto/request/LoginFactorRequest.java
?? src/main/java/com/security/exception/AuthFlowException.java
?? src/main/java/com/security/security/PublicAuthRoutes.java
?? src/main/java/com/security/service/LoginChallengeService.java
?? src/main/resources/db/migration/V51__login_challenges.sql
?? src/main/resources/db/migration/V52__prevent_totp_replay.sql
?? src/test/java/com/security/controller/AuthEndpointSecurityTest.java
?? src/test/java/com/security/controller/AuthLoginContractTest.java
?? src/test/java/com/security/controller/AuthPersistenceIntegrationTest.java
?? src/test/java/com/security/controller/AuthSensitiveDataTest.java
?? src/test/java/com/security/controller/TotpExposureProductionTest.java
?? src/test/java/com/security/security/AuthJwtContractTest.java
?? src/test/java/com/security/service/LoginChallengeServiceTest.java
?? src/test/java/com/security/service/TotpSlotValidationTest.java
?? src/test/java/com/security/support/LocalPostgresIntegrationTest.java
?? src/test/resources/db/test-baseline/V1__legacy_test_schema.sql
```

### git diff --stat

```text
 .../java/com/security/config/SecurityConfig.java   |  11 +-
 .../com/security/controller/AuthController.java    | 251 +++---------
 .../controller/GlobalExceptionHandler.java         |  11 +-
 .../controller/PasswordResetController.java        |   2 +-
 .../security/controller/SecureAuthController.java  | 309 ---------------
 .../controller/SecurePasswordResetController.java  |  13 +-
 .../security/controller/TwoFactorController.java   | 250 ++----------
 .../security/interceptor/SecurityInterceptor.java  |   6 +-
 .../security/repository/BackupCodeRepository.java  |   1 +
 .../com/security/repository/UserRepository.java    |   4 +
 .../security/security/JwtAuthenticationFilter.java |  44 +--
 .../com/security/security/JwtTokenProvider.java    |  56 +--
 .../java/com/security/service/AuthService.java     |  66 ----
 .../com/security/service/BackupCodeService.java    |   6 +-
 .../com/security/service/LoginSecurityService.java |  42 +-
 .../com/security/service/PasswordResetService.java |  22 +-
 .../com/security/service/SecureJwtService.java     | 435 ---------------------
 .../security/service/SessionManagementService.java |  28 +-
 .../java/com/security/service/TotpService.java     |  83 ++--
 .../com/security/service/TwoFactorService.java     | 206 +++-------
 .../service/automation/JobRunnableFactory.java     |  16 +-
 src/main/resources/application.yml                 |   3 +-
 .../controller/OrderOwnershipIntegrationTest.java  |   2 +-
 .../AdminDemandForecastControllerWebMvcTest.java   |   2 +-
 src/test/resources/application-test.yml            |  14 +-
 25 files changed, 295 insertions(+), 1588 deletions(-)
```

Stat excluye archivos nuevos sin seguimiento; status identifica creados, modificados y eliminados. Ambas ramas y los tres stashes permanecen intactos; no commit/push/PR/deploy ni cambios móviles/de dependencias
