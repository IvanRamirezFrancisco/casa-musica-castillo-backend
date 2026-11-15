# Railway Production Cutover — Casa de Música Castillo

> Fecha de verificación: `2026-11-15`  
> Rama candidata backend: `chore/backend-stability-audit` (PR #11 -> `deploy/railway-vercel-alexa`)  
> Worktree fuente de verificación: `.local-worktrees/spring-public-runtime`

---

## 1. Arquitectura y Topología Objetivo

| Componente | Origen anterior (Render) | Destino productivo (Railway / actual) | Estado verificado |
|---|---|---|---|
| **Backend Spring Boot** | `https://casa-musica-castillo-backend.onrender.com` (expirando / timeout observado) | `https://casa-musica-castillo-backend-production.up.railway.app` | `UP` en `/actuator/health` (`HTTP 200`) |
| **Stack Runtime** | Java 21, Spring Boot 4.0.9, Tomcat (`max=24`, `min-spare=2`), Hikari (`maximum-pool-size=4`, `minimum-idle=1`) | Igual (`Dockerfile` con `maven:3.9.6-eclipse-temurin-21-alpine` -> `eclipse-temurin:21-jre-alpine` + `postgresql18-client`) | Verificado en `Dockerfile`, `railway.json`, `application-production.yml` |
| **Base de datos** | PostgreSQL en Neon | PostgreSQL en Neon (esquema Flyway **V56**, TLS) | Verificado en runtime Railway + pruebas locales V56 |
| **Frontend Web (Vercel)** | `https://casa-musica-castillo.vercel.app` apuntando a `.onrender.com/api` | Actualizado a `https://casa-musica-castillo-backend-production.up.railway.app/api` | Compilado y probado en rama `chore/remove-academic-modules` (PR #63) |
| **App Móvil Android** | `PRODUCTION_API_BASE_URL = 'https://casa-musica-castillo-backend.onrender.com'` | Actualizado a `https://casa-musica-castillo-backend-production.up.railway.app` | CORS `https://localhost` validado (`HTTP 200`, `ACAC=true`) |
| **Integraciones externas conservadas** | Brevo HTTPS, Cloudinary, Supabase Storage, Mercado Pago Sandbox | Sin cambios de proveedores ni migración destructiva de datos | Conservado sin exponer secretos |

---

## 2. Configuración Railway Verificada

### 2.1 `Dockerfile` y `railway.json`
- Builder: `DOCKERFILE` (`railway.json`, `dockerfilePath: "Dockerfile"`).
- Puerto dinámico: `ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]`.
- Healthcheck Railway: `/actuator/health`, timeout `300s`, `restartPolicyType: "ON_FAILURE"`, `restartPolicyMaxRetries: 3`.
- JVM (`Dockerfile` `ENV JAVA_OPTS`): `-Xms64m -Xmx224m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m` (sobrescribible en variables de entorno de Railway si se ajusta memoria en Hobby 1 vCPU / 2 GiB).
- Herramientas en contenedor runtime: `postgresql18-client` instalado (`pg_dump`/`pg_restore`), usuario no-root `appuser` (`uid=1001`).

### 2.2 Variables de entorno obligatorias y de control en Railway

> **Regla de seguridad:** Nunca copiar valores reales de secretos (`DB_PASSWORD`, `JWT_SECRET`, claves de Mercado Pago, Brevo, Cloudinary o Supabase) en archivos versionados.

```env
SPRING_PROFILES_ACTIVE=production
BACKEND_PUBLIC_BASE_URL=https://casa-musica-castillo-backend-production.up.railway.app

# Deshabilita programación automática SOLO en esta instancia sin alterar ops.system_automations en Neon
APP_JOBS_BACKUP_DATABASE_ENABLED=false
APP_JOBS_INVENTORY_AUDIT_ENABLED=false

# Mantener true en la única instancia productiva activa; usar false temporalmente solo en réplicas pasivas
APP_MERCADOPAGO_RECONCILIATION_ENABLED=true
```

Adicionalmente conservar en Railway las variables operativas de Neon (`SPRING_DATASOURCE_URL` / `DB_*` con SSL), JWT, MFA/TOTP, `CORS_ORIGINS` (incluyendo `https://casa-musica-castillo.vercel.app` y `https://localhost`), Brevo (`SPRING_MAIL_*` / API HTTPS), Cloudinary, Supabase Storage y Mercado Pago Sandbox.

---

## 3. Verificación de Respaldo y Recuperación (Prerrequisito Flyway V56)

1. **Antecedente de Codex (V53/V51):** Restauración previa verificada sobre respaldo real del `2026-11-13` (SHA-256 `c4828777b49f6c100ec1d99b07a61bce843d4a0d7067c1803dacfcc0988c6aa2`), llegando en dicho corte a Flyway V53/V51.
2. **Verificación local ejecutada sobre Flyway V56 (`2026-11-15`):**
   - Artefacto probado: `target/render-free-lab/final-scheduled-synthetic.dump` (`288,292` bytes, `724` entradas TOC, PostgreSQL `18.7`, SHA-256 `BDAC85D1D5BE7032D33C9D678D083291CC2B4FDB1EE36FA342B3D92FD67B642E`).
   - Restauración aislada en base desechable `v56_restore_verify` dentro de contenedor `cmc-final-audit-tests-pg`:
     - `pg_restore --no-owner --no-privileges --exit-on-error` -> exit code `0`.
     - **Tiempo medido de restauración local (RTO de laboratorio):** `833.68 ms` (~`0.83 s`).
     - Versión Flyway verificada tras restauración: **V56** (`56 | track account deletion outcomes | t`), con V52–V56 íntegras.
     - Integridad estructural verificada: `61` tablas de aplicación (`public, auth, security, catalog, sales, customer, ops, analytics`), `67` claves foráneas (`contype = 'f'`), `0` restricciones sin validar (`NOT convalidated`), `5` registros en `ops.system_automations`, `6` usuarios y `8` productos en el dataset sintético.
3. **Acción obligatoria externa en Neon antes de deshabilitar `BACKUP_DATABASE_JOB` definitivamente en producción:**
   - Debido a que la estación local no contiene credenciales administrativas de la API/Consola de Neon, antes de depender exclusivamente de PITR se debe confirmar en **Neon Console -> Settings -> Postgres -> History window**:
     1. Ventana efectiva de historial configurada (p. ej. 24 h / 7 días según plan Neon), que define el **RPO objetivo**.
     2. Crear una rama temporal (branch / PITR restore) del estado actual **Flyway V56** de producción sin sobrescribir la rama principal.
     3. Confirmar en esa rama aislada la integridad de `auth.users`, `catalog.products`, `sales.orders`, `sales.order_items` y `public.flyway_schema_history` (`version = '56'`).
     4. Medir y registrar el tiempo real de creación de rama / conexión (**RTO operativo en Neon**).
   - Solo una vez verificada esa recuperación V56 en Neon, mantener activa en Railway la variable `APP_JOBS_BACKUP_DATABASE_ENABLED=false`. Mientras tanto, el respaldo manual desde el panel administrativo sigue operativo.

---

## 4. Sondas Reales Ejecutadas contra Railway (`2026-11-15`)

Sondas HTTP con cabecera `Origin: https://casa-musica-castillo.vercel.app`:

| Endpoint | HTTP | Latencia medida | CORS / Observación |
|---|---|---|---|
| `GET /actuator/health` | `200` | `554 ms` | `ACAO: https://casa-musica-castillo.vercel.app` |
| `GET /api/public/categories/active` | `200` | `357 ms` | Catálogo real desde Neon |
| `GET /api/public/products/latest` | `200` | `366 ms` | Respuesta real |
| `GET /api/public/products/featured` | `200` | `348 ms` | Respuesta real |
| `GET /api/public/products/catalog?page=0&size=4` | `200` | `407 ms` | Producto real retornado (ej. ID `313`) |
| `GET /api/public/products/313` | `200` | `259 ms` | Detalle real |
| `GET /api/public/products/313/recommendations` | `200` | `441 ms` | Recomendaciones reales |
| `GET /api/public/legal/registration` | `200` | `287 ms` | Contenido legal V55/V56 |
| `GET /api/public/legal/deletion` | `200` | `108 ms` | Política eliminación |
| `GET /api/public/help/categories` | `200` | `535 ms` | Categorías ayuda |
| `GET /api/public/help/faqs` | `200` | `529 ms` | FAQs activas |

Preflights `OPTIONS` verificados:
- `Origin: https://casa-musica-castillo.vercel.app` -> `200`, `Access-Control-Allow-Credentials: true`
- `Origin: https://localhost` (Capacitor Android WebView) -> `200`, `Access-Control-Allow-Credentials: true`
- `Origin: capacitor://localhost` y `http://localhost` -> `403` (rechazados por política estricta; Android usa `https://localhost`).

---

## 5. Regla de Escritor Único (Convivencia Temporal con Render)

Para evitar ejecuciones concurrentes peligrosas sobre Neon mientras Render siga encendido como respaldo de rollback:
1. Mantener **un solo** servidor ejecutando jobs de escritura (`OrderExpirationJob`, `MercadoPagoReconciliationJob`, `SESSION_CLEANUP_JOB`, `ACCOUNT_CLEANUP_JOB`).
2. Durante la aceptación inicial en Railway:
   - Si Render permanece levantado en pasivo, colocar en Render `APP_MERCADOPAGO_RECONCILIATION_ENABLED=false` y suspender su scheduler o apagar cron en Render sin borrar la base de datos.
   - No modificar `ops.system_automations` en Neon solo para Railway, ya que ambos entornos comparten temporalmente esa tabla.
3. Conservar Render disponible para rollback rápido hasta completar las pruebas de aceptación web, móvil y consola Google Play.
