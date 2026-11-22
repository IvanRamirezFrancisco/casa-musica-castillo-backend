# Consolidación local del backend — 20 de noviembre de 2026

> Actualización posterior: este documento conserva la evidencia histórica de la consolidación. El uso de `cmc_development:55439` como base de desarrollo queda sustituido por la conexión original `security_db:5432`. Sigue `LOCAL_DATABASE_RECOVERY.md`; no reinicialices clústeres ni uses el baseline de pruebas en la base original. V53–V59 están pendientes de autorización administrativa.

La fuente activa de desarrollo es `C:\Users\ivanf\Documents\7mo\Proyecto del login original y funcional\segundo proyecto`, en `fix/account-activation-hardening`. El móvil permanece en `C:\Users\ivanf\Documents\Desarrollo Movil Integral\casa-musica-mobile`, rama `feature/pen-dev-ui-unification`.

## Estado recuperado y preservación

| Árbol | Rama inicial | HEAD inicial | Trabajo pendiente inicial |
|---|---|---|---|
| Backend original | `codex/integrate-brevo-original` | `44df7468f65180e1aebda161eabe66d94355d95d` | 22 archivos modificados y 9 nuevos de código/pruebas/documentación |
| `spring-public-runtime` | `fix/account-activation-hardening` | `30a8c5ab9c85d58fe998935d7725edf3d2d3ba2f` | 44 archivos modificados/nuevos de endurecimiento |
| Móvil | `feature/pen-dev-ui-unification` | `c9e7258cf09b1a155dc0cd1340129bfae3412a29` | Rediseño y autenticación locales existentes, conservados |

No había cambios staged en estos árboles. Se inventariaron refs, stashes, configuración local, archivos nuevos/ignorados y worktrees antes de consolidar. El HEAD histórico confirmado era antecesor de la rama actualizada (0 commits exclusivos frente a 50). Sus modificaciones sin commit sí eran diferentes y se preservaron expresamente.

Respaldo privado externo: `C:\Users\ivanf\Documents\Respaldos privados Casa Musica\consolidacion-20261120-085432`. Acceso limitado al usuario y SYSTEM. No publicar esta carpeta: contiene configuración privada y evidencias originales.

| Archivo | Verificación SHA-256 |
|---|---|
| `backend-all-refs.bundle` | `7c77939287339d598a811ead1723b8b56d3c1a50a97698e482416ec2d82bb36c` |
| `mobile-all-refs.bundle` | `c456da486ccfbb22d9e6cf63ecf697a393012381b385fa0415dd62daee3178cf` |
| `original-tree.zip` | `78d03f091d14f20e1027b55a5ed631d5f8e464d180d733dcbe62e84549e21970` |
| `runtime-tree.zip` | `0dbbfd8769d8846e03bf1dce361ec1b10577527739ecdddc956790237af06d30` |
| `backend-git-metadata.zip` | `2abc570f36e520feb99d5d00783d8467e20d6f39b09f55864352825c02b42965` |

Se reabrieron los ZIP y se contrastó el SHA-256 de cada entrada con su manifiesto. Original: 45.726 archivos; runtime: 29.144; metadatos Git: 3.049. También se verificaron `mobile-tree.zip` y `mobile-android.zip`. Los bundles iniciales superaron `git bundle verify`. `verification.private.json`, los manifiestos y `RESTORE.private.txt` permiten comprobar/restaurar el estado previo. Se añadió una copia previa específica de los archivos de navegación del móvil.

Commits locales de preservación:

- `7a62157c6c4902753f998f1f97d39d5e2a3ebba8`: estado histórico completo, en su rama existente. Solo se normalizó una línea vacía final de documentación antes de confirmar; la copia exacta anterior está en el respaldo.
- `973da8bed2c38580ac2d55129c88d80a8ede840b`: 44 archivos del endurecimiento anterior, incluyendo V58/V59, servicios, pruebas y documentación, en `fix/account-activation-hardening`.

Se liberó la rama del worktree con `git switch --detach --no-overwrite-ignore`, y se activó en el original con `git switch --no-overwrite-ignore fix/account-activation-hardening`. No hubo force, reset, limpieza Git, cherry-pick masivo ni merge de implementaciones antiguas sobre las nuevas.

## Cambios históricos conservados y equivalencias

El commit histórico permanece accesible en su rama y en el bundle. No se sobrepusieron sus servicios síncronos a los nuevos outboxes.

- Límites BCrypt/UTF-8, DTO de administración y validación de creación de administradores: ya están en la versión actualizada. Se reincorporaron además las 13 pruebas `PasswordDependencyCompatibilityTest` del checkout histórico.
- Envío tras persistencia, errores de proveedor y recuperación: la implementación actual usa `VerificationService`, `SecurityMailQueue` y los estados ACCEPTED/FAILED/UNKNOWN. `ActivationHardeningIntegrationTest`, `PasswordSecurityIntegrationTest` y `EmailServiceTransportTest` cubren esos contratos. Las antiguas pruebas que esperaban registro HTTP 503 por rechazo del proveedor, acceso directo al token en texto o métodos privados de plantillas no se copiaron como si esos contratos siguieran vigentes; permanecen recuperables íntegramente en `7a62157`.
- Configuración y CI históricos: preservados en ese commit. Se conserva la configuración posterior de Spring Boot 4, JDK 21, dependencias y workflows de la rama actualizada.
- La configuración privada histórica `application-local.yml` fue trasladada, con hash verificado, a `.local-dev/legacy-application-local.yml.private`. No se utiliza en el arranque nuevo y también está en el ZIP original.
- Los artefactos anteriores del checkout original permanecen en `.local-dev/target-original-before-consolidation`. Los 28.565 archivos ignorados del runtime se trasladaron a `.local-dev/runtime-preserved` y se contrastó el SHA-256 de todos contra el manifiesto del respaldo. Después se retiró únicamente `spring-public-runtime` con `git worktree remove`, sin force. Ya no existe esa carpeta ni su registro Git.

## Entorno efectivo y arranque definitivo

| Servicio | Dirección/configuración |
|---|---|
| Angular Development | `http://localhost:4200` |
| Spring Boot actualizado | `http://localhost:8080`, perfil `local`, checkout original |
| PostgreSQL Development | `127.0.0.1:55439/cmc_development`, usuario local `cmc_dev` |
| PostgreSQL de la suite | Bases sintéticas nombradas en `prepare-local.py`, en 55439 y 55448 |
| Mailpit | SMTP `127.0.0.1:1025`, interfaz/API `http://localhost:8025` |

El PostgreSQL existente en 5432 no se modificó ni se migró. No se importaron usuarios ni datos comerciales: para probar el entorno aislado se registran cuentas sintéticas. Se conservaron las configuraciones anteriores para restauración privada.

Se identificaron y detuvieron exclusivamente el Java histórico PID 5516 y su Maven PID 11620. Se verificaron parentesco, perfil `local` y directorio original antes de detenerlos. El Angular existente PID 28860 se conservó. `runtime-process.json` y la inspección final de puertos documentan el proceso nuevo; los PID pueden cambiar después de reiniciar.

Preparación local (una vez y después de reiniciar Windows si PostgreSQL está detenido):

```powershell
cd "C:\Users\ivanf\Documents\7mo\Proyecto del login original y funcional\segundo proyecto"
python scripts/prepare-local.py
```

Con Mailpit disponible en 1025/8025, se conserva el comando habitual:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

En otra terminal:

```powershell
cd "C:\Users\ivanf\Documents\Desarrollo Movil Integral\casa-musica-mobile"
npm start -- --configuration development --port 4200
```

No lanzar una segunda instancia si esos puertos ya están ocupados por las ejecuciones dejadas activas. Maven requiere JDK 21; se comprobó Maven 3.9.11 / Java 21.0.8.

`prepare-local.py` conserva claves privadas locales estables, verifica propiedad y `data_directory` de sus clústeres antes de escribir, y no se conecta a 5432. `--stop` solo detiene clústeres propios comprobados. `--tests` crea las bases requeridas por la suite. `--reset-tests` recrea únicamente esas bases desechables y conserva `cmc_development`; no ejecutarlo mientras la suite esté trabajando.

`application-local.properties` importa obligatoriamente `.local-dev/application.properties`; este último está ignorado por Git y protegido con ACL. El perfil local desactiva pagos y almacenamiento externo, vacía claves de proveedores y usa Mailpit. `LocalDevelopmentConfiguration` verifica URL, usuario, host/puerto, marca real de la base y transporte antes de Flyway. `migrate` valida el esquema existente y aplica pendientes; `validate` comprueba el resultado. No se ignoran migraciones. La base local vacía usa exclusivamente el baseline reconstruido de desarrollo/pruebas, seguido de las migraciones existentes; Production conserva sus ubicaciones originales.

Si variables de entorno externas intentan cambiar la base o el proveedor, el arranque local se rechaza. No se debe desactivar ese control: revisar la terminal y quitar esas variables. No se modificaron variables globales de Windows ni Railway.

## Login y capacidades

La implementación anterior de Login consultaba `ActivatedRoute.snapshot.returnUrl`. Una vista cacheada de Ionic podía conservar un destino privado anterior; tampoco se descartaba expresamente al mostrar la confirmación de cambio de contraseña. Las pruebas añadidas reprodujeron ambos fallos antes de corregirlos.

Login ahora obtiene el retorno de la URL de la visita actual y lo captura al enviar las credenciales. Ese destino se conserva durante MFA y se consume al terminar. Una visita normal, el cierre de sesión, la confirmación de contraseña y una sesión vencida llevan a Inicio después del nuevo Login. El guard sigue conservando el retorno de una entrada protegida legítima de invitado. Se aceptan solo rutas internas conocidas; se rechazan rutas de autenticación, destinos desconocidos, retornos anidados y redirecciones abiertas. No se modificaron las verificaciones MFA ni la sesión JWT.

La causa local de “Sin confirmar” era la ejecución histórica de 8080, cuyo `UserController` no incluía la garantía de revocación. La carpeta actualizada sí la devuelve. Angular ya rechazaba correctamente una garantía ausente; no se forzó el resultado a `true`.

Resultado real sanitizado con una sesión sintética autorizada:

```json
{"status":200,"success":true,"data":{"passwordChangeRevokesSessions":true}}
```

Se comprobó además CORS con `Origin: http://localhost:4200`; sin JWT el endpoint devuelve 401. La tarjeta diferencia carga, disponible, error temporal/reintento, sesión vencida, contrato incompatible y no soportado explícito. Una respuesta incompleta muestra “Contrato incompatible”; solo un booleano `true` del servidor habilita la operación.

## Evidencia funcional real

`python scripts/verify-local-auth.py` utiliza HTTP real, PostgreSQL real y Mailpit. No utiliza respuestas simuladas ni automatización del navegador. Informe sanitizado: `.local-dev/consolidation-api-evidence.json`.

- Registro sin JWT y activación tanto por OTP como por enlace.
- Login, Perfil y consulta autenticada de capacidades.
- Contraseña actual incorrecta: HTTP 400 sin invalidar la sesión válida.
- Dos cambios simultáneos: un HTTP 200 y un HTTP 401; una sola modificación confirmada.
- Dos JWT previos: HTTP 401 / 401 después del cambio.
- Una sola notificación de contraseña, con HTML y text/plain, recibida en Mailpit.
- Login con la contraseña nueva mantiene MFA por correo y requiere completar el segundo factor.
- Dos solicitudes simultáneas de recuperación producen un solo correo; respuesta pública equivalente para una cuenta inexistente.
- Enlace de recuperación dirigido a `http://localhost:4200/reset-password`; ruta web disponible.
- Recuperación confirmada; reutilización del enlace rechazada; JWT anterior rechazado; nuevo Login sigue exigiendo MFA.

La pantalla de confirmación centrada, su contador de seis segundos, el botón manual y la limpieza de temporizadores no se reconstruyeron. Se conservaron sus pruebas. Las plantillas HTML/texto comunes y los contratos Brevo simulados permanecen en la suite backend. No se realizó ningún envío Brevo real ni se atribuyó a Gmail/Outlook un renderizado comprobado solo por Mailpit.

## Validación y límites

La suite completa y el empaquetado se ejecutan desde la carpeta original, sin `skipTests`. La primera ejecución detectó agotamiento de las 20 conexiones que `initdb` seleccionó en este equipo. Se preservaron sus logs y se acotaron únicamente los pools del perfil test a 3 conexiones/minimum-idle 0 y la caché de contextos a 2. Los pools de Production no se ampliaron. Después se repitió sobre bases sintéticas limpias.

También se comprobaron el arranque sobre base vacía, la identidad real del destino y el orden migrate/validate; los rechazos iniciales de preflight se conservaron en `.local-dev`. No se desactivó ninguna comprobación para conseguir el arranque.

| Comprobación | Resultado de esta consolidación |
|---|---|
| Backend completo + empaquetado final | 239/239, 0 fallos, 0 errores, 0 omitidas; `BUILD SUCCESS`, 6:07 min |
| Clases de pruebas backend | 43; incluye PostgreSQL, V57→V58, V58→V59, concurrencia, JWT, MFA, correo y contratos Brevo simulados |
| Pruebas de navegación/sesión móvil | 54/54 |
| Perfil, Seguridad, repositorio y Recuperación móviles | 51/51; 105/105 específicas contando el grupo anterior, sin solapamiento de archivos |
| Integración móvil, confirmación final | 268/268 |
| Regresión móvil final | 281/281 |
| Suite móvil completa final | 910/911; falla el buscador compacto de Catálogo en `public-catalog.integration.spec.ts:393` |
| Lint móvil final | Aprobado |
| Builds Development / Production finales | Aprobados, salidas separadas en `dist/consolidation-*-final` |
| `test:build-config` | 9/9 |
| API real + PostgreSQL + Mailpit | Aprobado; informe sin secretos en `.local-dev/consolidation-api-evidence.json` |

En una tanda de integración bajo carga se observaron 266/268: el fallo conocido del Catálogo y una espera fija de la prueba DEMO de Pedidos. Sin modificar pruebas ni módulos, la suite completa siguiente aprobó Pedidos y la repetición controlada de integración aprobó 268/268, incluido Catálogo. Se conservan los logs de todos esos intentos. El fallo de Catálogo de la suite completa sigue declarado; un reintento aprobado en otra agrupación no lo borra. No se relajan tiempos ni aserciones.

Un primer build Development encontró un bloqueo de permisos sobre la salida generada `www/assets/icon`. La compilación final se ejecutó correctamente en una salida nueva dentro de `dist`, sin alterar permisos ni borrar archivos de trabajo. Los avisos de presupuesto SCSS ya presentes no se convirtieron en errores ni se elevaron sus límites.

SHA-256 del JAR final: `a71e35767ca7cebe2ead85a3724cf1a9915a417574d646725218954091fc7d26`. Detalle de clases, tiempos y estado Git: `.local-dev/consolidation-test-summary-final.json` y `.local-dev/consolidation-final-state.json`. Logs e informes de las primeras ejecuciones permanecen preservados; no se borraron las pruebas intermitentes del móvil.

No se ejecutó una nueva auditoría OWASP en esta consolidación. Un reporte anterior no acredita el JAR actual. Antes de promover a producción deben completarse los quality gates de CI, resolver los fallos intermitentes documentados y efectuar la revisión visual manual.

## Verificación visual manual pendiente

No se automatizó el navegador porque esa capacidad estuvo restringida; no se intentó eludirla. La validación real de API no se presenta como un E2E visual de Ionic. No se generaron capturas nuevas ni se atribuyeron capturas antiguas al entorno consolidado.

En Chrome, con cuenta sintética nueva: Login normal → Inicio; entrada intencional a Pedidos/Favoritos → Login/MFA → destino; Perfil → Seguridad → tarjeta habilitada → formulario → cambio → Login con confirmación → Inicio. Abrir el enlace de Mailpit y comprobar confirmación, contador 6→0 y navegación manual/automática. Verificar Back Android por separado en un dispositivo conectado.

Revisar 320–1280 px, horizontal, texto 150/200 %, safe areas, teclado, foco, movimiento reducido y correo en Mailpit. No se acredita Android físico, App Links nativos ni renderizado Gmail/Outlook.

## Revisión y futura promoción

Los commits son locales. El móvil conserva sus cambios pendientes para revisión; no se mezcló código backend en ese repositorio. Se contrastaron con el inventario inicial `main`, los refs `develop` existentes, tags y stashes: permanecen iguales. Backend `main` sigue en `30a8c5ab9c85d58fe998935d7725edf3d2d3ba2f`; no existía una rama local `develop` en ese repositorio y no se creó ni eliminó. Móvil `main` permanece en `723546d413418cc5407129566d1513876e6a669e` y `develop` en `f9b3693af0e9c7297f0944a4b0f834521ac76750`.

Quedan 13 worktrees backend registrados: el checkout original activo y 12 históricos/de colaboradores conservados. Ninguno ocupa `fix/account-activation-hardening` fuera del checkout original. El inventario completo está en `.local-dev/worktrees-final.txt`. No se eliminaron otros directorios ni ramas.

Archivos de esta continuación backend: `.gitignore`, `AGENTS.md`, `docs/LOCAL_CONSOLIDATION.md`, `scripts/prepare-local.py`, `scripts/verify-local-auth.py`, `src/main/java/com/security/config/LocalDevelopmentConfiguration.java`, `src/main/resources/application-local.properties`, `src/test/java/com/security/config/LocalDevelopmentConfigurationTest.java`, `src/test/java/com/security/security/PasswordDependencyCompatibilityTest.java`, `src/test/resources/application-test.yml` y `src/test/resources/spring.properties`. Las 31 piezas históricas y las 44 piezas del endurecimiento anterior se revisan por separado en sus commits de preservación.

El proceso final comprobado de Spring Boot era PID 21776, puerto 8080, `user.dir` en la carpeta original; Angular seguía en PID 28860, puerto 4200. Estos PID corresponden a la comprobación final y cambian al reiniciar. La preparación local se ejecutó otra vez de forma idempotente: conservó base y claves.

Antes de integrar: revisar los commits de preservación y consolidación, los diff del móvil y la evidencia; completar CI/OWASP y validación visual; revisar variables de staging sin copiar secretos; probar Brevo solo con autorización de staging; respaldar la base del futuro entorno y aplicar V58/V59 mediante su procedimiento de despliegue. Esta tarea no autoriza promoción ni rollback de migraciones sobre datos reales.

No hubo push, merge, despliegue, cambios en Railway, Brevo productivo, PostgreSQL comercial, Google Play, firma ni versiones Android.
