# Recuperación y cambio de contraseña — candidata local

## Alcance y continuidad

Se continuó sobre `fix/account-activation-hardening`, HEAD `30a8c5ab9c85d58fe998935d7725edf3d2d3ba2f`, con los cambios locales de activación V58 conservados. No se creó checkout, rama, worktree ni clon. No se confirmó, publicó, integró ni desplegó código. El móvil continúa en `feature/pen-dev-ui-unification`, HEAD `c9e7258cf09b1a155dc0cd1340129bfae3412a29`.

El diagnóstico inicial de «No disponible» no demostraba ausencia de endpoint: `GET /api/users/security-settings` ya proporciona `data.passwordChangeRevokesSessions`. Angular convertía los errores de consulta en `false`. Ahora distingue carga, error recuperable, contrato no soportado y disponibilidad confirmada. La autorización del cambio sigue dependiendo del servidor y de la contraseña actual. No se fuerza la capacidad. No se puede atribuir a una versión concreta de Railway la captura histórica sin sus metadatos de ejecución.

## Cambios incrementales

- `SecurePasswordResetService` conserva validación de contraseñas y usa bloqueo de cuenta para consumo atómico. `PasswordResetService` es el adaptador de los contratos antiguos a esa misma implementación; se eliminaron dos políticas de emisión independientes.
- Tokens nuevos: 48 bytes de `SecureRandom`, Base64 URL sin padding, digest SHA-256 con prefijo de propósito `pr1:`. La entropía del token permite un digest no recuperable por diccionario práctico. Nunca se almacena el bearer en claro en la tabla de tokens.
- V59 convierte también los tokens históricos a ese digest, sin cambiar los enlaces enviados ni su caducidad. Se conserva lectura de tokens antiguos en claro para transición. No se mezclan tokens de activación, Login MFA o recuperación.
- El consumo adquiere el bloqueo de usuario y vuelve a leer el token: expiración, uso previo, estado de entrega y elegibilidad. La precisión de emisión se ajusta a microsegundos de PostgreSQL. Solo un consumo concurrente puede ganar.
- Recuperación rechaza cuentas inactivas, bloqueadas, expiradas o pendientes de registro; nunca las activa ni altera sus factores MFA. Un cambio confirmado invalida todos los enlaces de recuperación y desafíos Login pendientes, y revoca las sesiones persistentes.
- `UserService.changeOwnPassword` conserva la reautenticación, bloqueo y revocación existentes. Añade presupuesto persistente y vuelve a validar el JTI dentro del bloqueo para rechazar una petición concurrente cuya sesión ya fue revocada.
- `SecurityMailQueue` usa `ActivationCrypto` (AES-GCM, AAD por operación), `VerificationRequestLimit`, el scheduler existente y el transporte de un intento de `EmailService`. No añade dependencias, pools ni hilos nuevos. No mantiene transacciones durante SMTP/HTTP.

## Entrega y recuperación tras fallos

La intención se confirma en PostgreSQL antes de responder o cambiar la contraseña. La cola reclama mediante `FOR UPDATE SKIP LOCKED` y confirma `SENDING` antes de llamar al proveedor. Conserva resultados `ACCEPTED`, `FAILED`, `UNKNOWN` y `CANCELLED`, borrando el payload después del intento. ACCEPTED significa aceptación del proveedor, no entrega al buzón.

Un enlace anterior sigue válido hasta que el proveedor acepta el reemplazo. Un rechazo confirmado invalida solo el candidato nuevo. Un resultado ambiguo permite usar un mensaje que sí haya llegado, conservando el anterior y sin reenviar automáticamente. Un trabajador detenido en SENDING pasa a UNKNOWN después de cinco minutos; PENDING sobrevive reinicios. No se garantiza entrega exactamente una vez: una caída antes del envío, pero después de reclamar, puede dejar una operación incierta sin correo. Se requiere una nueva solicitud explícita respetando límites.

La notificación de un cambio confirmado nunca convierte un fallo posterior de SMTP/Brevo en un falso fallo del cambio. Un presupuesto agotado aplaza la intención aún no enviada con `next_attempt_at`; otros destinatarios siguen progresando. Esto no reintenta una llamada de proveedor ambigua. Las intenciones caducan y el mantenimiento elimina registros temporales por lotes.

Brevo se mantiene como proveedor de Production; SMTP/Mailpit se usa localmente. Recuperación y notificaciones no utilizan el fallback general que podría duplicar mensajes después de un timeout. Se conservan los flujos y contratos MFA existentes.

## Límites y respuesta pública

- IP de solicitud: 30 por ventana de cinco minutos, compartidas entre instancias y reinicios.
- Destinatario elegible: cooldown configurable `app.security.password-reset.cooldown-seconds` (predeterminado 60, mínimo efectivo 30); máximo tres intenciones en cinco minutos; cuota diaria reutilizada de `app.activation` (predeterminada 10).
- Cuotas globales compartidas con activación: predeterminadas 100/minuto y 1.000/día, configuradas por la política existente.
- Reautenticación del cambio: cinco intentos por cuenta y sesión en cinco minutos. Una contraseña incorrecta consume intento; no revoca una sesión válida. No es un bloqueo permanente.
- Verificación y consumo públicos también tienen presupuesto IP. Las rutas antiguas usan las mismas comprobaciones y el mismo resolvedor de IP.
- `ActivationClientAddress` utiliza el peer salvo proxies explícitamente permitidos. No confía en X-Forwarded-For de clientes arbitrarios. Los identificadores de contadores usan HMAC y separación por operación.
- Cuentas inexistentes, suspendidas y elegibles reciben la misma respuesta pública de solicitud. Los límites del destinatario no cambian esa respuesta. El 429 por IP incluye Retry-After y es independiente de la existencia de una cuenta. No hay envío a cuentas inexistentes o suspendidas.

Las muestras de latencia locales son evidencia observable, no una garantía matemática de tiempo constante ni una prueba contra ataques distribuidos. El límite global puede ser agotado por abuso distribuido; protección de borde y monitoreo siguen siendo necesarios.

## Contratos y navegación

Se conservan POST `/api/auth/forgot-password`, GET `/api/auth/validate-reset-token`, POST `/api/auth/reset-password`, POST `/api/auth/password-reset/request`, GET `/api/auth/password-reset/verify/{token}`, POST `/api/auth/password-reset/confirm` y POST `/api/users/change-password`.

Angular añade `/reset-password?token=…` usando el formulario conectado existente, sin campo editable de token ni servicio alternativo. Retira el token del historial visible, exige confirmación explícita y limpia secretos al salir. La pantalla inicial elimina la captura manual y ofrece solicitud neutral, cooldown y reenvío explícito. El aviso posterior al cambio autenticado también funciona cuando Ionic entra después de terminar la navegación Angular.

Se añadió `referrer=no-referrer`. Los servidores web, proxies, APM y analítica deben omitir/redactar query strings y segmentos con tokens en estas rutas. La aplicación no puede borrar un access log que el proxy haya escrito antes de cargar Angular. No deben persistirse cuerpos de petición ni cabeceras Authorization.

## Archivos de esta continuación

- Persistencia: `entity/PasswordResetToken.java`, `repository/PasswordResetTokenRepository.java`, migración V59 y nuevo `service/SecurityMailQueue.java`.
- Servicios: `SecurePasswordResetService.java`, adaptador `PasswordResetService.java`, `UserService.java`, `SessionManagementService.java`, `EmailService.java`, `VerificationRequestLimit.java`, `ActivationJobs.java`.
- HTTP: `PasswordResetController.java`, `SecurePasswordResetController.java`, `GlobalExceptionHandler.java`.
- Tests: `PasswordSecurityIntegrationTest.java` (nuevo), `PasswordSchemaUpgradeTest.java` (nuevo), `AuthEmailFlowsIntegrationTest.java`, `AuthEndpointSecurityTest.java`, `EmailServiceTransportTest.java`, `ActivationSchemaUpgradeTest.java` (conserva su objetivo V58 explícito).
- `.github/workflows/build.yml`: añade la base desechable requerida por la prueba V59. Este documento.

Todos los paths Java anteriores son relativos a `src/main/java/com/security/`, los tests a `src/test/java/com/security/` y las migraciones a `src/main/resources/db/migration/`. El resto del diff de activación ya estaba presente; no debe presentarse como trabajo nuevo.

## Validación y evidencia

- Suite Maven final: **219/219**, cero fallos, errores u omitidas, 41 clases, **4 min 14 s**. Incluye PostgreSQL real, concurrencia de reset/cambio, 15 pruebas de activación, 18 de Login MFA y 11 de transporte. No se usó skipTests.
- Flyway: instalación limpia hasta V59, prueba V57→V58 conservada y V58→V59 con token anterior, digest, caducidad y constraints. Spring/JPA inicializaron con el esquema final.
- JAR SHA-256: `e43fc54fb958efaf8ffae7e4be7edabfd66249d6ace88194c76124e9c621bdb8`.
- Angular: específicas **55/55**, integración **260/260**, regresión **271/271**, completa **890/891**. El fallo de la completa está en `public-catalog.integration.spec.ts:393`, búsqueda compacta, ya reportado antes y no modificado por esta tarea. La integración final pasó, por lo que el fallo conserva comportamiento intermitente. No se eliminó ni relajó su aserción.
- Lint, Development, Production y test:build-config **9/9** aprobados. Dos primeros intentos de build fallaron con EPERM al crear assets dentro del sandbox; se repitieron normalmente con permiso de ejecución y generaron artefactos completos. Se conservan advertencias SCSS de presupuestos anteriores sin ampliar los umbrales.
- OWASP Dependency-Check 13 ejecutado el 19 de noviembre: **91 dependencias**, **0 vulnerabilidades reportadas**, **0 supresiones**, CVSS 7 conservado. NVD/KEV/RetireJS consultados. OSS Index no disponible por falta de credenciales; no equivale a auditar esa fuente. SonarCloud/quality gate remoto no ejecutado.
- Chrome real con el mismo JAR final: Login→Olvidé contraseña→Mailpit→enlace→formulario→recuperación→nuevo Login; enlace consumido rechazado; Perfil→Seguridad→consulta de capacidad con fallo de red inducido→reintento real→contraseña actual incorrecta→cambio correcto→cierre local→nuevo Login. Los JWT de A y B devolvieron **401/401**. Un correo de recuperación y dos notificaciones (una por cada cambio confirmado), sin contraseñas en el contenido. Cero peticiones Railway.
- Capturas reales: 14, con campos sensibles vacíos. Formulario comprobado sin overflow horizontal a 320, 360, 375, 390, 412, 430, 600, 768, 960 y 1280 px; capturas también en ventana baja/orientación horizontal. Esto no constituye una prueba de teclado Android ni de texto ampliado en dispositivo físico.


Aceptación API final sobre una base nueva `password_acceptance_test`: dos JVM reales, un correo de recuperación bajo solicitudes simultáneas, consumo con un solo ganador (200/400), nuevo Login con MFA aún obligatorio y verificación EMAIL mediante Mailpit, respuestas públicas equivalentes, falsificación de X-Forwarded-For sin evasión, 429 con Retry-After compartido y persistente tras reiniciar ambas JVM, y una intención PENDING entregada una sola vez después del reinicio. Los payloads terminales quedaron borrados.

Muestras de solicitud, en milisegundos (tres por caso): elegible en cooldown 31,99 / 24,77 / 22,57; suspendida 9,34 / 17,06 / 28,74; inexistente 19,71 / 6,94 / 15,96. Tienen solapamiento y pocos datos; no prueban ausencia de canal temporal. La consulta de elegibilidad sigue siendo síncrona, aunque el proveedor no participa en la respuesta. Validar este riesgo con carga y distribución de latencias en staging antes de afirmar resistencia temporal; no se implementó una demora artificial ni se debilitó el límite para medir más peticiones.

Los primeros recorridos detectaron y permitieron corregir la pérdida del aviso visual de Login. Dos intentos posteriores del harness API fallaron por usar un nombre de propiedad y un selector de correo que no coincidían con el contrato MFA existente; se corrigió solo la herramienta de prueba. La aceptación final se ejecutó en una base nueva para evitar payloads de pruebas anteriores cifrados con otra clave efímera. No se cambió MFA para satisfacer esas herramientas.

Evidencia local: `password-browser-evidence.json`, `password-api-evidence.json`, `password-validation-summary.json`, `password-backend-tests.json`, galería `password-evidence.html` y 14 PNG bajo `C:/Users/ivanf/.codex/visualizations/2026/11/16/01a1e6c5-73a1-7da1-999b-2b1ab6c10033/`. Se revisaron visualmente formulario móvil, 320 px, tablet, diálogo y Login con confirmación; los demás tamaños tienen comprobación automatizada de overflow. Los patrones de JWT y enlaces con bearer dieron cero coincidencias en los logs finales de aceptación; no es una auditoría universal de logs. No se consideran pruebas contra Brevo real las simulaciones de transporte, ni Android físico las comprobaciones de Chrome. Los scripts bajo `target/` y `dist/` son herramientas locales ignoradas; no contienen credenciales comerciales y no forman parte del commit.

## Promoción futura y riesgos

1. Revisar el diff de V58 y V59, pruebas y contratos antes de aprobar una integración. Confirmar que V59 sigue siendo el siguiente número libre.
2. Preparar copia de seguridad y validar migraciones sobre una copia aislada actualizada. No editar migraciones históricas ni ejecutarlas manualmente en producción.
3. Desplegar de forma coordinada: V59 transforma los bearers históricos a digest; un binario antiguo que busque texto plano dejaría de aceptar esos enlaces. No mezclar escritores antiguos/nuevos ni volver al binario anterior sin una estrategia específica. Preferir corrección hacia adelante; restaurar un backup puede perder cambios posteriores y requiere decisión operativa.
4. Configurar los secretos existentes, compartidos entre instancias: `APP_SECURITY_JWT_SECRET`, credenciales PostgreSQL, `PICKUP_CODE_ENCRYPTION_KEY`; nunca incluir valores en Git. Rotar el secreto JWT afecta tanto sesiones como payloads pendientes y claves de límites: planificar antes de rotarlo.
5. Revisar `APP_BASE_URL` HTTPS público con fallback SPA de `/reset-password`, `EMAIL_PROVIDER=brevo`, `APP_EMAIL_BREVO_API_KEY`, remitente autorizado, CORS y configuración explícita de proxies. Mantener workers habilitados y límites seguros. ProductionGuard sigue rechazando origen de prueba/proveedor incorrecto.
6. Realizar un smoke test Brevo solo en staging autorizado, con destinatarios de prueba y secretos del entorno. Las pruebas locales no autorizan mensajes productivos.
7. Monitorear PENDING antiguo, UNKNOWN, FAILED, antigüedad SENDING, rechazos de cuota, sesiones revocadas, tamaño de tablas, latencia y conexiones. No incluir tokens ni destinatarios completos en alertas.
8. Completar App Links verificados y aceptación Android física por separado. La ruta web funcional no demuestra apertura automática nativa. La sesión web permanece en memoria y no se añadieron JWT a localStorage/sessionStorage.
9. Completar quality gates remotos autorizados y resolver el fallo previo del buscador compacto del móvil antes de una promoción que exija todas las suites al 100 %. No se modificó esa prueba ni ese módulo en esta tarea.


## Estado Git de entrega

Backend: 42 archivos modificados/nuevos en conjunto con la activación previa, sin archivos preparados; HEAD y `main` conservan `30a8c5ab9c85d58fe998935d7725edf3d2d3ba2f`. No existe una rama local `develop` en este checkout. Móvil: 73 archivos modificados/nuevos incluyendo el rediseño previo, sin archivos preparados; HEAD `c9e7258cf09b1a155dc0cd1340129bfae3412a29`, main `723546d413418cc5407129566d1513876e6a669e`, develop `f9b3693af0e9c7297f0944a4b0f834521ac76750`. No se modificaron estas referencias. `git diff --check` aprobó; se conservan avisos de conversión LF/CRLF sobre archivos móviles anteriores. No hubo commit, push, merge ni despliegue.

Candidata funcional validada localmente, **sin aprobación de promoción productiva**: faltan gates remotos, aceptación de App Links/Android, smoke Brevo en staging y resolución/triage del fallo completo de Catálogo. Mantener visible el riesgo temporal indicado y comprobar presupuesto de memoria/carga en staging; no se acredita rendimiento productivo con estos recorridos.
