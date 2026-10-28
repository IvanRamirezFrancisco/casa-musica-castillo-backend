# Continuación de autenticación y correo — 28 de octubre de 2026

## A. Estado inicial recuperado
Worktree: backend-auth-email-worktree. Rama: fix/auth-registration-email-recovery.
HEAD: 44df746. Había 19 archivos versionados modificados y 3 archivos nuevos de Antigravity, todos conservados.
No había Maven activo. El Java encontrado era GradleDaemon y se dejó intacto.
Había otros procesos PostgreSQL: no se tocaron. pg_ctl confirmó apagado el clúster temporal de este worktree.
El último reporte Surefire y target/mvn-email-flows.log indicaban 6 pruebas, 3 fallos, 0 errores, 0 omitidas.
La evidencia anterior se copió a target/email-evidence-before-20261028-024714 antes de repetir pruebas.

## B–D. Errores, causas y correcciones
Todos los fallos originales eran AssertionFailedError: expected true, obtained false.

| Línea original y prueba | Esperado por la prueba | Asunto productivo comprobado | Corrección |
|---|---|---|---|
| 218 — registrationVerificationAndResendFullCycle | Verifica tu cuenta - Casa de Música Castillo | Verificación de cuenta - Casa de Música Castillo | Igualdad exacta con el contrato productivo |
| 497 — passwordRecoveryResetSessionRevocationAndTokenSingleUse | Recuperación de Contraseña - Casa de Música Castillo | Recuperación de contraseña - Casa de Música Castillo | Igualdad exacta; diferencia de mayúscula |
| 663 — twoFactorEmailCodeSendCaptureAndVerify | Código de Verificación (2FA) - Casa de Música Castillo | Código de verificación 2FA - Casa de Música Castillo | Igualdad exacta; texto, mayúscula y paréntesis |

Se inspeccionó EmailService antes de cambiar expectativas. No se cambió el formato productivo ni se relajaron controles de autenticación.

Al superar las aserciones originales aparecieron otros defectos de pruebas:
- Verificación esperaba mensajes distintos para token inválido/expirado. AuthController responde intencionalmente con el mismo mensaje genérico. Ambas expectativas ahora comprueban ese contrato y conservan HTTP 400 y success=false.
- La expresión regular omitía el guion bajo, válido en Base64URL. Un token de recuperación quedaba truncado y la API devolvía 400 correctamente. El extractor conserva el token completo.
- El correo sintético inexistente no se incluía en limpieza. Sus intentos persistían entre ejecuciones y producían 429. Se limpia únicamente ese fixture en la base temporal y se incluye en tearDown.
- El código incorrecto fijo 000000 podía coincidir con el generado aleatoriamente. Ahora se escoge determinísticamente uno distinto.

## E. Archivos cambiados en esta continuación
- src/test/java/com/security/controller/AuthEmailFlowsIntegrationTest.java (archivo nuevo preexistente de Antigravity, continuado).
- src/test/java/com/security/controller/EmailMimeCaptureTest.java (nuevo).
- src/test/java/com/security/service/EmailServiceTransportTest.java (nuevo).
- AUTH_EMAIL_CONTINUATION_REPORT.md (este informe).

No se editaron archivos productivos, Angular, TOTP ni códigos de recuperación durante esta continuación.

## F–G. Ejecuciones
- Primera repetición de los tres afectados: 3 pruebas, 2 fallos nuevos expuestos; 2FA aprobada.
- Repetición de los otros dos tras corregir contratos y extracción: 2/2 aprobadas.
- Correo completo + MIME + HTTPS: 15 pruebas, 0 fallos, 0 errores, 0 omitidas.
- AuthEmailFlowsIntegrationTest: 7 pruebas aprobadas: los 6 casos originales y un caso adicional de expiración de recuperación.
- Autenticación relacionada: 64 pruebas, 0 fallos, 0 errores, 0 omitidas.
- Suite completa y empaquetado: 220 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS. JAR target/auth-system-1.0.0.jar generado y reempaquetado.

Comandos principales:

    mvn "-Dtest=AuthEmailFlowsIntegrationTest,EmailMimeCaptureTest,EmailServiceTransportTest" test -Ddependency-check.skip=true
    mvn "-Dtest=Auth*Test,LoginChallengeServiceTest,TotpExposureProductionTest,TotpSlotValidationTest" test -Ddependency-check.skip=true
    mvn verify -Ddependency-check.skip=true

En las dos últimas ejecuciones se forzaron adicionalmente claves Brevo/Resend vacías y SMTP en 127.0.0.1:1 por propiedades JVM. La prueba SMTP sobrescribe estas propiedades dinámicamente con su puerto local de captura.
Dependency-Check/NVD se omitió explícitamente; no se omitieron pruebas.

## H–L. Estado funcional y recepción
- Registro: válido, validaciones, duplicados, cuenta pendiente, correo generado y login previo a activación rechazado.
- Verificación: enlace local normalizado, token inválido/expirado rechazado, rotación por reenvío, activación, uso único y redirección GET.
- Recuperación: solicitud para cuenta existente/inexistente, recepción local, enlace completo, validación, restablecimiento, revocación de sesión previa, rechazo de contraseña anterior y reutilización. El nuevo caso expira el token en PostgreSQL y comprueba rechazo sin cambiar la contraseña.
- 2FA por correo: generación, envío SMTP, extracción, verificación y JWT; nuevo challenge expirado en PostgreSQL rechazado con 401; límite de cinco intentos del código de perfil. Las pruebas relacionadas cubren persistencia, concurrencia, límites, expiración y consumo del challenge.
- Caída SMTP: 503 real, cuenta pendiente conservada y recuperación posterior mediante reenvío.

La recepción se prueba con socket SMTP real en 127.0.0.1, puerto efímero. Se comprueban destinatario, asunto decodificado, contenido y enlace/código. El capturador almacena el mensaje antes de responder 250 a DATA: las aserciones posteriores al envío no necesitan esperas ni polling.

MIME: texto ISO-8859-1, HTML UTF-8 con base64, multipart mixed/alternative anidado, mensaje message/rfc822 y exclusión de binarios. Jakarta Mail decodifica charset y transferencia; el extractor recorre las partes. No se aplica toString a objetos MIME ni se acepta el mensaje crudo como sustituto de una interpretación fallida.

## M–N. Render e infraestructura pendiente
Render Free bloquea salidas SMTP 25, 465 y 587:
[Documentación oficial de Render](https://render.com/docs/free)

EmailService ya implementa Brevo HTTPS (api.brevo.com/v3/smtp/email) → Resend HTTPS (api.resend.com/emails) → SMTP.
Se comprobaron contratos HTTP de Brevo/verificación, Resend/recuperación, fallback Brevo rechazado → Resend/2FA y fallo total propagado.
Las respuestas HTTP se interceptaron localmente: no prueban credenciales, aceptación real ni entrega externa.

Configuración existente:
- APP_EMAIL_BREVO_API_KEY o BREVO_API_KEY.
- APP_EMAIL_RESEND_API_KEY o RESEND_API_KEY.
- Remitente verificado mediante EMAIL_SENDER_ADDRESS / APP_EMAIL_SENDER_ADDRESS y URL frontend APP_BASE_URL / FRONTEND_URL.

No se accedió ni modificó Render. No se contrataron servicios ni generaron credenciales.
Falta comprobar en el entorno real que uno de esos proveedores esté configurado, que el remitente esté verificado y que se reciba un mensaje externo. SMTP solo no sirve en esos puertos en Render Free.
SendGrid/Mailgun aparecen en configuración/dependencias, pero la cadena activa inspeccionada no implementa esos transportes; configurarlos solos no habilita esta ruta.

## O. Seguridad y aislamiento
PostgreSQL exclusivamente en 127.0.0.1:55439 con usuario auth_test y bases backend_hardening_test/auth_challenges_test.
La clase base fuerza la configuración de base de datos por encima del entorno. No se usaron datos o credenciales productivos.
La prueba SMTP vacía los nombres correctos de las propiedades Brevo/Resend e intercepta RestTemplate sin expectativas de salida.
Los correos sintéticos con dominio público terminan en el capturador local.
MockMvcPrint.NONE evita volcar cuerpos con contraseñas, tokens y JWT en fallos de estas pruebas. Comparaciones de rotación no imprimen tokens completos.
Los nuevos archivos contienen únicamente valores sintéticos; la búsqueda de patrones de credenciales reales no encontró coincidencias.
Los reportes históricos de fallos pueden contener datos sintéticos en dumps de MockMvc: se preservaron como evidencia, no se borraron.
Permanece la advertencia de compatibilidad de Flyway con PostgreSQL 18.1; las migraciones y pruebas funcionaron. No se abordó la auditoría de dependencias independiente.

## P. Estado Git y controles finales
Rama original conservada; security/backend-dependency-hardening no se modificó.
git diff --check: sin errores (solo avisos Git de conversión LF/CRLF).
Sin cambios en migraciones anteriores.
Sin commit, push, PR, merge ni deploy.
Los 19 cambios versionados y los 3 archivos nuevos originales se conservaron.
Angular no fue modificado: sus pruebas no aplican a esta continuación.
Estado final: 19 archivos versionados modificados preexistentes y 6 nuevos (3 preexistentes, 2 pruebas añadidas y este informe). Sin Maven activo. Clúster temporal iniciado por esta sesión detenido al finalizar; bases, datos temporales y evidencia conservados. No se detuvieron los otros PostgreSQL ni GradleDaemon.

## Q. Veredicto
LISTO PARA REVISIÓN del backend local. Registro, verificación, recuperación y 2FA por correo aprobados. La entrega real desde Render permanece pendiente de validación de configuración externa; no se declara resuelto el incidente productivo.

Evidencia final: target/mvn-email-complete-2.log, target/mvn-auth-related.log, target/mvn-backend-verify.log, target/surefire-reports y target/git-status-final.txt.

## Preparación posterior de publicación en GitHub
Esta sección corresponde a la publicación autorizada después del informe local anterior.

- Repositorio: IvanRamirezFrancisco/casa-musica-castillo-backend.
- Rama exclusiva de publicación: fix/auth-registration-email-recovery.
- Base del PR: security/auth-hardening, HEAD remoto 44df7468f65180e1aebda161eabe66d94355d95d, idéntico al punto de partida. PR apilado sobre #2, sin modificarlo. La base de #2 es deploy/railway-vercel-alexa; main no contiene esa misma historia y no existe develop.
- security/backend-dependency-hardening permanece en su worktree independiente sin incorporar su trabajo pendiente.
- Revalidación previa: mvn verify con 220 pruebas, cero fallos, errores u omitidas, BUILD SUCCESS; finalizó el 28/10/2026 a las 03:08:14 America/Mexico_City. Dependency-Check/NVD omitido, sin afirmar aprobación de vulnerabilidades.
- Manifiesto SHA256 de los 25 candidatos comparado sin cambios tras las pruebas. Revisión de secretos reales sin hallazgos; las pruebas contienen exclusivamente fixtures sintéticos. No había commits locales inéditos anteriores a los nuevos commits de esta corrección.
- Workflows actuales: SonarQube ejecuta verificación/análisis en PR; Security Pipeline solo recibe PR a main y push a main/master. Ninguno contiene pasos de despliegue. No se modifican workflows ni se deshabilitan controles.
- Render está documentado sobre deploy/railway-vercel-alexa. La rama de corrección y la base del PR son distintas. GitHub no mostró hooks configurados ni checks de proveedores de despliegue en el HEAD base; el check SonarQube previo estaba fallido y no se presenta como aprobado.
- Fuera de commits: target completo (JAR, logs, Surefire, manifiesto, evidencia y PostgreSQL), archivos locales ignorados y cualquier archivo del otro repositorio.
- Frontend: repositorio casa-musica-castillo-frontend, rama fix/product-reviews-tracking. Tiene 14 archivos versionados modificados y archivos nuevos de PWA/documentación. Se conserva íntegro y requiere revisión/PR independiente; no se publica desde este trabajo.
- Antes del merge: revisar el PR de base #2 y este diff, resolver o diagnosticar CI sin omitir controles, comprobar proveedor HTTPS/remitente/URL del frontend en Render y validar entrega externa en una actividad posterior autorizada. Este PR no autoriza merge ni deploy.