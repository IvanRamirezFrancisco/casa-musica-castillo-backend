# Registro y activación — cierre local de endurecimiento

Fecha: 18 de noviembre de 2026. Estado: candidata local para revisión; **no autorizada para despliegue**. No hubo push, merge ni modificación de Railway.

## Estado recuperado y alcance

Se continuó en el checkout preexistente del backend, rama **fix/account-activation-hardening**, HEAD base **30a8c5ab9c85d58fe998935d7725edf3d2d3ba2f**. Al recuperar había 26 archivos de código/configuración/pruebas modificados o nuevos, sin preparar y sin commit. La carpeta histórica y sus cambios no se alteraron. No se crearon ramas, clones ni worktrees en esta continuación.

El empaquetado interrumpido había terminado con **197 pruebas: 196 aprobadas y un error**. El error era la preparación incompleta de esquemas de ActivationSchemaUpgradeTest antes de V44, no un fallo SQL de V58. Su JAR seguía siendo antiguo. No se acreditó ese intento como compilación aprobada.

La evidencia anterior de 15 y 36 pruebas se conservó, sin presentarla como ejecución nueva. Los informes Surefire contenían también resultados históricos: el total se obtuvo del log de cada ejecución completa, no sumando indiscriminadamente todos los XML antiguos.

El frontend permanece en **feature/pen-dev-ui-unification**, HEAD **c9e7258cf09b1a155dc0cd1340129bfae3412a29**, con todo el rediseño anterior sin commit. No se cambió su código funcional o visual en esta continuación.

## Correcciones adicionales de la continuación

1. Se prepararon schemas y search_path en la prueba V57 → V58, igual que en las pruebas PostgreSQL existentes. No se editaron V1–V57.
2. Se hizo indivisible la reserva de los tres presupuestos de entrega mediante un savepoint JDBC dentro de la transacción de emisión. Si se agota la cuota global, no se consume la cuota diaria del destinatario.
3. Se rechazó la confianza de proxy universal (/0).
4. Production rechaza el procesamiento de activación deshabilitado y proveedores de pruebas como SMTP.
5. Se añadió a CI la base sintética activation_upgrade_test que necesita la nueva prueba. No se modificaron quality gates.
6. Se cerró una ruta real de reactivación administrativa: disableUser ahora toma el mismo bloqueo de cuenta, retira la condición de auto-registro pendiente e invalida desafíos y payloads temporales. Conserva la comprobación administrativa existente y su auditoría.
7. Se agregaron aserciones/regresiones específicas para esas correcciones y se completaron las comprobaciones reales con dos JVM y Angular.
8. El cooldown toma la última emisión independientemente de su estado: un rechazo confirmado no habilita otro envío inmediato. Se conservó el límite de 30 s y se añadió una aserción de regresión.

## Arquitectura conservada y garantías

Se mantienen los cinco contratos: POST /api/auth/register, POST /api/auth/resend-verification, POST /api/auth/verify-email-code, POST /api/auth/verify-email y GET /api/auth/verify?token=… . No hay endpoints, servicios de autenticación ni pantallas duplicados. Registro y activación no emiten JWT. El usuario debe pasar por Login y por MFA cuando está configurado.

UserService conserva las validaciones y la aceptación legal, crea cuentas inicialmente deshabilitadas y no sobrescribe credenciales ni datos ante duplicados. El trabajo de contraseña se realiza también para solicitudes duplicadas válidas, sin almacenar el hash nuevo en una cuenta existente. Un registro repetido ya no activa un reenvío.

VerificationRequestLimit usa UPSERT condicional atómico en PostgreSQL. Las claves de IP/operación/destinatario se protegen con HMAC, no hashes de correos sin secreto. El límite IP se aplica antes de conocer elegibilidad; no se crean contadores por cada destinatario inexistente. Las cuotas de destinatario y entrega solo se reservan para desafíos elegibles. La limpieza elimina hasta 10.000 ventanas vencidas por ejecución.

ActivationCrypto deriva claves separadas para limitación y payload a partir del secreto compartido de servidor. El payload recuperable se cifra con AES-GCM, nonce aleatorio de 96 bits y el digest del desafío como AAD. Contiene únicamente el material necesario para una entrega pendiente; no es una copia permanente del OTP en claro. Se borra al terminar el intento, al cancelar/activar y durante recuperación/expiración. Las instancias necesitan el mismo secreto; cambiarlo requiere un plan de rotación y drenaje de la cola.

El OTP mantiene SecureRandom, seis dígitos como cadena, ceros iniciales, HMAC-SHA256 vinculado a cuenta y enlace, comparación MessageDigest.isEqual y cinco intentos persistentes. Los enlaces mantienen digest versionado y compatibilidad con el formato anterior. El reloj de activación es inyectable; las pruebas temporales usan Clock controlado.

## Entrega y recuperación

El desafío y el payload cifrado se guardan en la transacción de registro/reenvío. La petición pública no llama al proveedor. ActivationJobs reutiliza el scheduling existente; no se agregó librería, servicio externo ni bucle de espera activa.

| Estado | Efecto y recuperación |
|---|---|
| PENDING | Intención persistida; puede reclamarse después de reiniciar. Si venció, no se envía. |
| SENDING | Un trabajador lo reclama con FOR UPDATE SKIP LOCKED y confirma la reclamación antes de HTTP/SMTP. |
| ACCEPTED | El transporte confirmó aceptación. Se retiran los desafíos anteriores; no se afirma entrega al buzón del destinatario. |
| FAILED | Rechazo confirmado o imposibilidad de preparar la entrega. Se consume el nuevo desafío y se conserva el anterior que siga vigente. |
| UNKNOWN | El proveedor pudo aceptar, o el proceso cayó después de reclamar. Se borra el payload; no se reenvía automáticamente. |
| CANCELLED | Cuenta activada/deshabilitada o intención expirada/cancelada; no se envía de nuevo. |

La recuperación de SENDING sin respuesta se realiza después de cinco minutos. Es conservadora: una caída antes de enviar puede perder ese intento, pero no dispara una entrega duplicada. El usuario conserva el reenvío explícito sujeto a las cuotas. No se prometen entrega exactamente una vez ni recuperación automática de un resultado desconocido.

La nueva ruta de activación de EmailService hace **un intento por un transporte**. Brevo 2xx es aceptación; un rechazo 4xx seguro se clasifica FAILED; timeout, fallos ambiguos y 5xx son UNKNOWN. No se prueba el siguiente proveedor ni se repite el timeout. SMTP de desarrollo clasifica rechazo previo seguro cuando puede acreditarlo; el resto es incierto. Las rutas de MFA y recuperación conservan su comportamiento previo.

La llamada de red ocurre fuera de la transacción y del bloqueo de usuario. La finalización vuelve a comprobar cuenta/destinatario. Una respuesta tardía no reabre una cuenta y un desafío antiguo no sustituye uno posterior aceptado.

## Política y configuración

Propiedades bajo app.activation; los límites originales se conservaron como punto de partida. Las propuestas de 60 s o 3/15 min no se aplicaron silenciosamente.

| Propiedad | Valor por defecto | Semántica |
|---|---:|---|
| cooldown-seconds | 30 | Intervalo de emisión por cuenta |
| window-seconds | 300 | Ventana deslizante de emisión por cuenta |
| emissions-per-window | 3 | Emisiones por cuenta en esa ventana |
| recipient-daily | 10 | Cuota por destinatario elegible, día UTC |
| global-per-minute | 100 | Presupuesto compartido por minuto |
| global-daily | 1000 | Presupuesto compartido por día UTC |
| otp-seconds | 900 | OTP de 15 minutos; no admite configurar más de 900 |
| link-seconds | 86400 | Enlace nuevo de 24 horas |
| register-ip | 30 | Registro por IP/ventana fija de 300 s |
| resend-ip | 30 | Reenvío por IP/ventana fija de 300 s |
| verify-ip | 60 | Verificación por operación/IP/300 s |
| dispatch-ms | 1000 | Poll de envío, lote máximo de 10 |
| cleanup-ms | 3600000 | Limpieza de ventanas y tokens vencidos |
| jobs-enabled | true | Obligatorio en Production |
| trusted-proxies | vacío | No confiar en X-Forwarded-For por defecto |

Un 429 público incluye Retry-After conforme a su ventana, sin señalar si existe la cuenta. Los límites internos de elegibilidad conservan una respuesta pública neutral. El frontend ya soporta este encabezado y no necesitó cambios.

ActivationClientAddress recorre la cadena de derecha a izquierda únicamente cuando el peer inmediato está permitido. Rechaza headers múltiples/ambiguos, entradas no literales y cadenas excesivas. No consulta DNS para nombres del cliente. Confiar en /0 está prohibido. No se cambió la infraestructura de Railway: sigue pendiente verificar su peer real y aprobar los CIDR exactos. Sin esa configuración se conserva getRemoteAddr, seguro frente a spoofing pero con posible agrupación de clientes detrás del proxy.

Production exige el proveedor Brevo, su clave y un remitente configurados, un origen HTTPS público y forwarding automático deshabilitado. El guard no admite desactivar los workers ni un poll inferior a 100 ms. La validación existente de JwtTokenProvider exige al menos 64 bytes de secreto. No se introdujeron credenciales de desarrollo como fallback productivo.

## Migración V58

V58 es aditiva: code_expires_at, delivery_state, delivery_payload, delivery_started_at y auth.activation_request_limits; PK compuesta de contadores, constraint de estados e índices de cola/expiración.

Los enlaces existentes conservan token y expiry_date. Los códigos anteriores se limitan a LEAST(expiry_date, created_at + 15 minutos), sin extender los vencidos. Un código sin vencimiento independiente no se acepta por omisión. Los valores por defecto permiten que un servidor anterior inserte enlaces sin las columnas nuevas.

Se comprobó PostgreSQL limpio y una base sintética V57 actualizada a V58, Flyway migrate/validate, conservación de datos y compatibilidad de columnas. La inicialización Spring/JPA también pasó. No se aplicó la migración a producción.

## Matriz de hallazgos y evidencia

| ID | Riesgo | Corrección / archivos principales | Evidencia | Estado y límite |
|---|---|---|---|---|
| SEC-01 | Límite IP en memoria | VerificationRequestLimit, ActivationCrypto, V58 | Concurrencia PostgreSQL, dos JVM y reinicio de ambas | Implementado; el ataque distribuido exige controles perimetrales adicionales |
| SEC-02 | IP falsificada/proxy | ActivationClientAddress, application.yml | ActivationBoundaryTest: peer no confiable, cadena, ambigüedad, /0 | Código probado; aceptación del proxy real pendiente |
| SEC-03 | Reenvío invalida código antes de entregar | VerificationService y estados V58 | confirmedFailureKeepsOldCodeAndLinkUsable | Implementado |
| SEC-04 | Timeout confundido con rechazo | EmailService.deliverActivation | Transporte HTTP simulado y estado UNKNOWN PostgreSQL | Implementado; no existe garantía SMTP exactamente una vez |
| SEC-05 | Dos workers/correos repetidos | Reclamación persistente SKIP LOCKED | Dos workers, dos JVM, concurrencia real y Mailpit | Un intento por operación; reenvío explícito genera otra operación autorizada |
| SEC-06 | Estado/503 del proveedor revela elegibilidad | Entrega fuera de respuesta pública y mensajes neutrales | HTTP/JSON iguales; latencias locales para activa/inexistente/pendiente | Mitigado; no se certifica indistinguibilidad temporal absoluta |
| SEC-07 | OTP válido 24 h | code_expires_at y Clock | Antes/en límite, OTP vencido con enlace válido, migración histórica | Implementado, 15 minutos por defecto |
| SEC-08 | Espera pública imprecisa | ActivationLimitException, AuthExceptionHandler | MockMvc verifica Retry-After 73; 429 real tras límite compartido | Implementado, sin metadatos por cuenta inexistente |
| SEC-09 | Activación/reenvío/cancelación concurrentes | Bloqueo de cuenta, consumo conjunto y disableUser | Ganador único y deshabilitación administrativa | Implementado |
| SEC-10 | Clientes/enlaces anteriores rotos | DTO/rutas existentes y migración aditiva | Parser Angular, GET legado, flujo real OTP/enlace | Compatible por contrato; no prueba Android físico v1.2 |
| SEC-11 | SMTP/forwarding inseguro en Production | ActivationProductionGuard | Configuración insegura rechazada; desarrollo real con Mailpit | Código probado; variables/edge productivos no inspeccionados |
| SEC-12 | Regresión Login/MFA/registro | Servicios existentes y pruebas completas | Suite Maven y Angular específica | Aprobado localmente; SonarCloud remoto no ejecutado |

## Pruebas y aceptación local

Los resultados definitivos se resumen junto a este documento en la evidencia de cierre. Se preservaron los intentos anteriores y sus errores; no se borraron ni relajaron pruebas.

| Comprobación final | Resultado |
|---|---|
| Maven package, suite completa de 39 clases | **200/200**, 0 fallos, 0 errores, 0 omitidas; 2 min 37 s |
| Flyway limpio y actualización V57 → V58 | **Aprobados**, 56 migraciones aplicadas, versión máxima 58 y cero fallos en cada base verificada |
| Angular específico | **80/80**: 74 autenticación/repositorio/OTP/errores y 6 parser de enlaces |
| Lint Angular | **Aprobado**, repetido |
| Build Development local con API loopback | **Aprobado**, repetido sin cambiar environment de producción |
| test:build-config | **9/9**, repetido |
| Development/Production, regresión e integración de la fase frontend anterior | Builds aprobados, 266/266 regresión, 255/256 integración y 885/886 completa; fuente funcional sin cambios, no repetidas todas |
| Dependency-Check 13 | **BUILD SUCCESS**, 91 dependencias, 0 hallazgos y 0 entradas suprimidas; advertencia OSS Index |
| SonarCloud/quality gate remoto | **No ejecutado** en esta continuación local |
| Android físico / Railway / Brevo real | **No ejecutados** |

El fallo previo del buscador compacto del Catálogo permanece identificado en la evidencia frontend anterior. No se borró ni se debilitó esa prueba ni se modificó ese módulo. No se presentan 291 tests como total: había XML históricos adicionales; el log de la ejecución final acredita exactamente 200.

La aceptación completa de dos JVM confirmó límites compartidos tras reiniciar ambas, recuperación de PENDING, concurrencia y Mailpit. Después del último ajuste aislado del cooldown se volvió a empaquetar toda la suite y se comprobó el **JAR final** con registro concurrente, activación/Login entre JVM y ambos recorridos de Angular. En este último recorrido se indujo un fallo de transporte en Chrome: el borrador se conservó, no hubo falso éxito y el reintento llegó al backend real. Hubo dos intentos de verificación desde el navegador, uno abortado antes del servidor y uno confirmado; cero reenvíos automáticos.

SHA-256 del JAR final: `196c0387100fd041131b17dd9925e3b2a0fa6bc34ef2a40a8ebcbaa060a47dc8`. No se copia este JAR a Git. Los escaneos de patrones de OTP/enlace/JWT/contraseña sobre los logs finales dieron cero coincidencias; no sustituyen una auditoría universal de todos los logs de infraestructura.

- Backend: JDK 21, Spring Boot 4.0.9, Maven 3.9.11 y PostgreSQL 18. Empaquetado sin skipTests.
- Bases sintéticas verificadas en 127.0.0.1:55439 y 55448. Se usaron las bases de integración existentes y activation_upgrade_test / activation_e2e_test, nunca las comerciales.
- Mailpit de loopback 1025/8025; sin claves Brevo/Resend en los procesos de aceptación.
- Dos instancias reales del JAR en 18083/18084 compartiendo base y secreto; frontend Development en 14200.
- Registro concurrente sin sobrescritura; código con un ganador entre dos JVM; reenvío concurrente con un solo correo; 429 compartido y persistente después de reiniciar ambas JVM; cola PENDING procesada una vez después del reinicio.
- Navegador real: Registro → OTP → Login y Registro → Enlace → Login, sin interceptar respuestas exitosas, sin reenvíos automáticos ni llamadas a Railway. Las capturas evitan OTP, tokens y contraseñas reales.
- MFA se verificó con la suite backend, PostgreSQL y captura SMTP existentes. No se afirma un recorrido físico Android ni un Login MFA visual adicional.
- El transporte ambiguo se simuló dentro de pruebas, con PostgreSQL real. No se provocaron fallos reales de Brevo ni se enviaron correos a buzones externos.

OWASP Dependency-Check 13 pudo ejecutarse normalmente en la continuación. Analizó 91 dependencias, sin vulnerabilidades reportadas ni entradas suprimidas. NVD/KEV/RetireJS terminaron; OSS Index emitió advertencia de fuente no consultada por falta de credenciales. No se deshabilitó ese analizador ni se redujo CVSS 7 para obtener el resultado.

SonarCloud y el quality gate remoto no se ejecutaron: necesitan el workflow y sus credenciales autorizadas. La cobertura JaCoCo global se conserva como dato, no como aprobación de un quality gate. El análisis de dependencias no demuestra ausencia total de vulnerabilidades.

## Rendimiento y riesgos residuales

No se añadieron dependencias ni pools. Hikari usa máximo 5/minimum-idle 1; la cola usa scheduling existente y lotes acotados. No se mantiene conexión de cuenta durante correo. Cada poll vacío implica una lectura y dos actualizaciones de recuperación/expiración, además del control transaccional. Ajustar intervalos solo después de medir en staging.

La muestra local de dos JVM con -Xmx256m registró working sets de aproximadamente 356 y 532 MiB durante arranque/aceptación, con 39–58 hilos. Son observaciones del backend completo, no memoria atribuible a activación ni prueba de estabilidad bajo carga. No se aprueba un presupuesto productivo de 512 MiB a partir de esta muestra.

Las medianas de reenvío de una aceptación local inicial (seis muestras por estado) fueron aproximadamente 13–20 ms, con máximos de 27–34 ms. La entrega ya no bloquea la respuesta pública. El tamaño pequeño y la superposición no permiten certificar que no exista un canal estadístico de tiempo. No se utilizaron pausas artificiales.

Riesgos/condiciones de promoción:

- Verificar proxy real y allowlist antes de habilitar interpretación de headers.
- Verificar zona horaria UTC y semántica de timestamps históricos antes del rollout; no se convierten fechas de clientes reales por suposición.
- Prueba de capacidad/memoria sostenida y proveedor lento en staging; esta aceptación fue limitada.
- Restringir/redactar logs de infraestructura y query strings; las trazas SQL de conflictos únicos pueden contener identificadores y no deben publicarse.
- Asegurar permisos mínimos y retención de la tabla/payloads cifrados, incluyendo backups.
- El secreto compartido no se rota sin drenar/planificar la cola; una cola no descifrable no se reenvía inseguramente.
- UNKNOWN requiere supervisión/reenvío explícito. No hay promesa de aceptación exactamente una vez.
- Control perimetral contra botnets/DDoS sigue siendo necesario: los límites no reemplazan esos controles.
- La fuente OSS Index no consultada y el quality gate SonarCloud deben revisarse en CI antes de promoción.
- No hubo aceptación Android/App Links físicos ni prueba en Railway o con entrega real Brevo.

## Variables necesarias, sin valores

Conservar SPRING_PROFILES_ACTIVE, DATABASE_URL/SPRING_DATASOURCE_URL, usuario y secreto PostgreSQL, APP_SECURITY_JWT_SECRET/JWT_SECRET, APP_EMAIL_BREVO_API_KEY/BREVO_API_KEY, EMAIL_PROVIDER, EMAIL_FROM/APP_EMAIL_SENDER_ADDRESS y APP_BASE_URL/FRONTEND_URL. Revisar CORS_ALLOWED_ORIGINS y server.forward-headers-strategy.

Las propiedades app.activation admiten binding de entorno APP_ACTIVATION_…; documentar límites aprobados y CIDR explícitos para APP_ACTIVATION_TRUSTED_PROXIES. Mantener las claves MFA/cifrado ya existentes. Ningún valor secreto se incorpora a este informe ni a Git.

## PR propuesto y rollout

Título: fix(auth): persist activation limits and dispatch verification safely.

Descripción: El registro y reenvío podían depender del proveedor síncrono, perder límites entre instancias e invalidar el código anterior ante un fallo de correo. Se persiste la emisión y su reclamación, se clasifican resultados ambiguos sin fallback automático, se separa la expiración OTP y se preservan los contratos móviles. Se añade V58 aditiva, controles por entorno, revocación de desafíos al deshabilitar administrativamente y pruebas PostgreSQL/Mailpit/dos JVM. No incluye cambios comerciales.

Antes de promover:

1. Revisión del diff, matriz y credenciales/variables por el responsable; CI completo y quality gates.
2. Backup verificado y punto de recuperación. Confirmar versión Flyway y zona horaria; ensayar V57 → V58 sobre copia sintética representativa.
3. Confirmar permisos, índices, cuotas, remitente Brevo, URL HTTPS, CORS y proxies; staging con presupuesto de memoria medido.
4. Aplicar V58 y desplegar backend de forma controlada con una sola versión consumidora de cola. No hacer un rollout mixto prolongado con emisores antiguos.
5. Smoke autorizado de registro/activación/Login/MFA y una entrega sintética Brevo aprobada por separado. No se realizó aquí.
6. Vigilar 5xx/429, retraso PENDING, SENDING >5 min, UNKNOWN/FAILED, aceptación del proveedor, conexiones, heap/RSS y crecimiento/limpieza de ventanas.
7. Detener promoción ante duplicados de una operación, uso repetido aceptado, bypass MFA, bloqueo de usuarios legítimos por proxy, payload sin limpieza o caída sostenida de entregas.
8. Si hay que retirar la aplicación, detener primero el consumidor y preservar estados UNKNOWN; no retransmitirlos automáticamente. Mantener V58 y sus columnas para investigación/compatibilidad; no hacer rollback destructivo de DDL.
9. Un binario antiguo conserva contratos de enlace pero **no** el endurecimiento nuevo: no se considera rollback de seguridad equivalente. Antes de reanudar emisiones antiguas, evaluar su comportamiento síncrono y los desafíos/colas pendientes. Preferir una corrección hacia adelante revisada.

## Archivos y herramientas

Los archivos se enumeran en el inventario Git de la evidencia. Código nuevo: ActivationClockConfiguration, ActivationProductionGuard, ActivationLimitException, ActivationClientAddress, ActivationCrypto, ActivationJobs, ActivationPolicy y V58. Se reutilizan UserService, VerificationService, VerificationRequestLimit, EmailService, AuthController, AuthExceptionHandler, AuthMailContent y entidades existentes.

Pruebas nuevas: ActivationBoundaryTest, ActivationHardeningIntegrationTest, ActivationSchemaUpgradeTest. Se ampliaron pruebas de endpoints, persistencia, correo y transporte; no se eliminaron métodos de prueba.

target/activation-postgres.py, los arneses E2E, logs, JAR y datos locales son herramientas/evidencias ignoradas. No deben incorporarse al commit ni copiarse al código móvil. La única modificación de CI prepara una base sintética adicional. Todos los cambios se mantienen locales, sin commit, para revisión.

Referencias de diseño: [OWASP, respuestas consistentes y tokens de uso único](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html) y [PostgreSQL, SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html). Son fundamentos del diseño; los resultados del proyecto proceden de las pruebas descritas.
