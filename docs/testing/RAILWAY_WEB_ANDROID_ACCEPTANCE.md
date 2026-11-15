# Aceptación Backend Railway, Web Vercel y Android — Casa de Música Castillo

> Fecha de ejecución: `2026-11-15`  
> Backend destino: `https://casa-musica-castillo-backend-production.up.railway.app`  
> Web destino: `https://casa-musica-castillo.vercel.app` (PR #63 Preview: `https://casa-castillo-git-chore-remo-46f292-ivanfra12345-2986s-projects.vercel.app`)  
> Android paquete: `com.casademusicacastillo.app` (`versionCode 2`, `versionName 1.1`, `targetSdk 36`)

---

## 1. Matriz de Verificaciones Automatizadas Ejecutadas

### 1.1 Backend (`casa-musica-castillo-backend` — rama `chore/backend-stability-audit`, PR #11, commit `d113a5c`)

| Suite / Prueba | Alcance | Resultado exacto | Entorno |
|---|---|---|---|
| Suite focalizada Codex | `DynamicSchedulerServiceTest` (3), `MercadoPagoReconciliationJobTest` (2), `PaymentReconciliationIdempotencyTest` (1), `OrderExpirationJobTest` (5), `OrderServiceTransactionTest` (8) | **19/19 verdes** (0 fallos, 0 errores, 0 saltados) | Maven local + JUnit 5 |
| Suite completa Maven (`mvn test`) | Todas las clases Java unitarias e integración | **166/169** en corrida 1 + **3/3** en re-corrida limpia (`PublicProductCatalogPostgresTest` 2/2 + `Schema50UpgradeTest` 1/1) = **169/169 verdes** | Docker PostgreSQL 18 (`cmc-final-audit-tests-pg`, puertos `55439`/`55448`) |
| Sonar access check | `node --test src/test/js/check-sonar-access.test.mjs` | **9/9 verdes** | Node.js |
| Restauración aislada Flyway V56 | `final-scheduled-synthetic.dump` (`288,292` bytes, SHA-256 `BDAC85D1D5BE7032D33C9D678D083291CC2B4FDB1EE36FA342B3D92FD67B642E`) | **Exit 0**, RTO local **833.68 ms**, Flyway **V56**, 61 tablas, 67 FKs, 0 constraints inválidas | `pg_restore` -> DB temporal `v56_restore_verify` |
| GitHub Actions + Railway PR #11 | `Build and analyze` (`3m2s`), `Catalog pagination on PostgreSQL` (`49s`), `Check RASP files` (`4s`), `OWASP Dependency-Check` (`1m0s`), `nurturing-victory` Railway | **5/5 checks PASS** | GitHub Actions + Railway |

### 1.2 Frontend Angular (`casa-musica-castillo-frontend` — rama `chore/remove-academic-modules`, PR #63, commit `019a971`)

| Verificación | Resultado exacto | Observaciones |
|---|---|---|
| Compilación producción (`npm run build`) | **Exit 0** (`19.200 s`) | `DIST_ONRENDER_COUNT=0`, `DIST_RAILWAY_COUNT=1` |
| Pruebas unitarias (`npx ng test --watch=false --browsers=ChromeHeadless`) | **105/105 SUCCESS** (`Chrome Headless 157.0.0.0`) | 0 fallos, 0 errores |
| Vercel Preview Deployment (PR #63) | **HTTP 200** (`https://casa-castillo-git-chore-remo-46f292-ivanfra12345-2986s-projects.vercel.app`) | `ngsw.json` confirma host `casa-musica-castillo-backend-production` y `NGSW_HAS_ONRENDER=False` |
| SonarCloud Quality Gate (PR #63) | **Quality Gate passed** | 0 Security Hotspots |

### 1.3 Aplicación Móvil Ionic/Capacitor (`casa-musica-castillo-mobile` — rama `feature/mobile-account-security`, PR #100, commit base `a9f82f6`)

| Suite / Verificación | Resultado exacto | Duración |
|---|---|---|
| `npm run test:build-config` (`node --test test/android-build-config.test.mjs`) | **9/9 verdes** | `112.6 ms` |
| `npm run lint` (`ng lint`) | **Pass** (`All files pass linting.`) | - |
| `npm test -- --watch=false` (Vitest unit & integration specs) | **78 archivos, 806/806 pruebas verdes** | `29.82 s` |
| `npm run test:integration` (`ng test --configuration=integration`) | **13 archivos, 207/207 pruebas verdes** | `20.57 s` |
| `npm run test:regression` (`ng test --configuration=regression`) | **29 archivos, 202/202 pruebas verdes** | `15.26 s` |
| `npm run test:white-box` (`node test/run-white-box.mjs`) | **10 archivos, 125/125 pruebas verdes** | `13.86 s` |
| `npm run android:release` + `npm run android:verify-release` | **BUILD SUCCESSFUL (`1m 7s`), `status: "PASS"`** | `ASSETS_ONRENDER_COUNT=0`, `ASSETS_RAILWAY_COUNT=2` |

---

## 2. Pruebas Reales Contra Backend Railway en Vivo (`2026-11-15`)

Sondas ejecutadas contra `https://casa-musica-castillo-backend-production.up.railway.app` (Neon real):

| Operación / Endpoint | Origin simulado | HTTP | Latencia | Resultado / CORS |
|---|---|---|---|---|
| `GET /actuator/health` (previo a PR) | `https://casa-musica-castillo.vercel.app` | `200` | `554 ms` | `ACAO: https://casa-musica-castillo.vercel.app` |
| `GET /actuator/health` (post-deploy `d113a5c`, petición fría inmediata) | `https://casa-musica-castillo.vercel.app` | `502` -> `200` (warm) | - | Confirma regla de mantener Serverless OFF; en warm devuelve `UP {liveness, readiness}` |
| `GET /api/public/categories/active` | `https://casa-musica-castillo.vercel.app` | `200` | `357 ms` | Datos reales Neon |
| `GET /api/public/products/latest` | `https://casa-musica-castillo.vercel.app` | `200` | `366 ms` | Datos reales Neon |
| `GET /api/public/products/featured` | `https://casa-musica-castillo.vercel.app` | `200` | - | `ACAO: https://casa-musica-castillo.vercel.app` |
| `GET /api/public/products/catalog?page=0&size=2` | `https://localhost` (Capacitor Android) | `200` | `824 ms` | `ACAO: https://localhost` |
| `GET /api/public/products/catalog?page=0&size=2` | `https://casa-musica-castillo.vercel.app` | `200` | `362 ms` | `ACAO: https://casa-musica-castillo.vercel.app` |
| `GET /api/public/products/313` y `/recommendations` | `https://casa-musica-castillo.vercel.app` | `200` | `259 ms` / `441 ms` | Detalle y recomendaciones reales |
| `GET /api/public/legal/registration` | `https://casa-musica-castillo.vercel.app` | `200` | `287 ms` | Contrato legal V55/V56 |
| `GET /api/public/legal/deletion` | `https://localhost` | `200` | `108 ms` | `ACAO: https://localhost` |
| `GET /api/public/help/categories` y `/faqs` | `https://casa-musica-castillo.vercel.app` | `200` | `535 ms` / `529 ms` | Centro de ayuda activo |
| `GET /api/cart` y `GET /api/orders` (sin JWT) | `https://localhost` | `401` | - | Fail-closed protegido |
| `OPTIONS` CORS Preflight | `https://casa-musica-castillo.vercel.app` | `200` | - | `Access-Control-Allow-Credentials: true` |
| `OPTIONS` CORS Preflight | `https://localhost` | `200` | - | `Access-Control-Allow-Credentials: true` |
| `OPTIONS` CORS Preflight | `https://casa-castillo-git-chore-remo-46f292-ivanfra12345-2986s-projects.vercel.app` | `403` | - | Dominio preview no incluido en `CORS_ORIGINS`; requiere merge a `deploy/vercel-frontend` para probar navegador completo sobre `casa-musica-castillo.vercel.app` |

---

## 3. Alcance Externo Pendiente (No Simulado)

1. **Aceptación física en Samsung Galaxy A55:** `adb devices -l` no detectó ningún dispositivo USB conectado durante esta sesión (`List of devices attached` vacío). Por política estricta, la aceptación en hardware físico permanece **PENDIENTE** hasta conectar el equipo, instalar la APK firmada con `--enable-reviewed-commerce` si se probarán escrituras revisadas, y ejecutar login real, MFA, catálogo, carrito y verificación manual con cuenta autorizada.
2. **Prueba autenticada e-commerce E2E en navegador / móvil (Brevo, Cloudinary, Mercado Pago Sandbox):** No se almacenaron contraseñas de cuentas reales de prueba en variables de entorno (`CMC_PROBE_EMAIL` / `CMC_PROBE_PASSWORD` ausentes) y `REAL_CHECKOUT_WRITE_ENABLED` se conserva en `false` por defecto para evitar pedidos reales accidentales en Neon producción.
3. **Verificación PITR en Consola Neon (Flyway V56):** La consola Neon requiere sesión administrativa externa para consultar `Settings -> Postgres -> History window` y ejecutar branch restore directo de producción V56.
4. **Render (`https://casa-musica-castillo-backend.onrender.com/actuator/health`):** Presentó timeout al momento de las sondas iniciales.
