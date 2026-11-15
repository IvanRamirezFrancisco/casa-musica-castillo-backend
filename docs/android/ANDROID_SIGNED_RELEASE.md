# Android Release APK y AAB hacia Railway — Casa de Música Castillo

> Fecha de compilación: `2026-11-15`  
> Rama móvil: `feature/mobile-account-security` (PR #100)  
> Commit fuente limpio compilado (`sourceSha`): `a9f82f62811f6d6432867c17044d0eca90decfdd`  
> Origen API embebido: `https://casa-musica-castillo-backend-production.up.railway.app`

---

## 1. Metadatos de Empaquetado Verificados (`verification.json` / `build-report.json`)

| Propiedad | Valor verificado |
|---|---|
| **`applicationId`** | `com.casademusicacastillo.app` |
| **`versionCode`** | `2` |
| **`versionName`** | `1.1` |
| **`targetSdk`** | `36` (Android 16, requerido por Google Play) |
| **`environment`** | `production` |
| **`variant`** | `release` |
| **`sourceTreeDirty`** | `false` |
| **`debuggable`** | `false` |
| **`allowBackup`** | `false` |
| **`releaseCleartext`** | `false` (`network_security_config.xml` bloquea tráfico HTTP en claro) |
| **`sourceMaps`** | `false` |
| **`fixtureAndDiagnosticMarkersAbsent`** | `true` |
| **`r8BridgeMethodPreserved`** | `true` |
| **`r8SecureStorageMethodsPreserved`** | `true` |
| **`r8MappingSha256`** | `f6c1f2b7accee72ed23c33b8902c7f4a5366f8caec46a6abf66a20792f084110` |
| **`reviewedCommerce`** | `false` (compilación técnica estándar; ver sección 3) |
| **Referencias `onrender.com` en assets** | `0` (`ASSETS_ONRENDER_COUNT=0`, `ASSETS_RAILWAY_COUNT=2`) |

Permisos declarados en el manifiesto compilado:
- `android.permission.INTERNET`
- `android.permission.VIBRATE`
- `com.casademusicacastillo.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

---

## 2. Artefactos Generados (Técnicos Sin Firmar — Listos para Firma Upload Key)

Compilados mediante `npm run android:release` y validados con `npm run android:verify-release` (`status: "PASS"`):

| Artefacto | Ruta relativa | Tamaño (bytes) | Entradas ZIP | SHA-256 |
|---|---|---|---|---|
| **Release APK (sin firmar)** | `reports/android-packages/production-release/app-release-unsigned.apk` | `2,894,565` | `565` | `296c725ff2c442cae7f0e2a72e9f790308444f448c1078561686b6bbbf938495` |
| **Release AAB (sin firmar)** | `reports/android-packages/production-release/app-release.aab` | `3,367,279` | `571` | `35cecf985ef118a72aebcb0a9f326419531af9bd88fa9d975c8b22829b85e76f` |

---

## 3. Firma con Keystore de Subida Existente (`castillo-upload.p12`)

### 3.1 Llave de subida existente localizada (NO regenerada)
Se verificó la existencia de la llave de subida oficial en la estación local:
- Keystore: `C:\Users\ivanf\CMC-Keys\castillo-upload.p12` (`4,394` bytes)
- Certificado PEM público: `C:\Users\ivanf\CMC-Keys\castillo-upload-cert.pem` (`1,930` bytes)
- Alias esperado: `castillo-upload`
- Huella SHA-256 registrada del certificado de subida:
  `47:49:2A:A3:5B:3F:B2:61:73:BA:F1:EF:23:6C:3C:2B:EF:C3:3D:B8:E3:ED:C3:D5:2E:F0:C7:75:35:33:DB:C3`

> **Bloqueo limpio de seguridad:** La contraseña privada de `castillo-upload.p12` no está presente en archivos del repositorio ni en variables `CMC_UPLOAD_*` del entorno, por lo que no se realizó firma automática ni se creó ninguna llave alterna. El script oficial solicita las credenciales de forma interactiva en memoria (`SecureString`) sin escribirlas en disco ni en logs.

### 3.2 Comando exacto para generar el APK y AAB firmados

En PowerShell dentro de la raíz del worktree móvil:

```powershell
# Compilación estándar (lectura pública + login/perfil; escritura comercial bloqueada por interceptor)
.\scripts\sign-android-release.ps1
```

Si se requiere compilar con **escrituras revisadas de carrito/favoritos habilitadas** (`--enable-reviewed-commerce` en `build-android.mjs`):

```powershell
.\scripts\sign-android-release.ps1 -EnableReviewedCommerce
```

Variables opcionales de entorno aceptadas por `scripts/build-android.mjs` (en sesión efímera, nunca en archivos):
- `CMC_UPLOAD_KEYSTORE_PATH` (default: `C:\Users\ivanf\CMC-Keys\castillo-upload.p12`)
- `CMC_UPLOAD_KEY_ALIAS` (default: `castillo-upload`)
- `CMC_UPLOAD_CERT_SHA256` (`47:49:2A:A3:5B:3F:B2:61:73:BA:F1:EF:23:6C:3C:2B:EF:C3:3D:B8:E3:ED:C3:D5:2E:F0:C7:75:35:33:DB:C3`)
- `CMC_UPLOAD_STORE_PASSWORD` / `CMC_UPLOAD_KEY_PASSWORD` (o captura segura vía prompt interactivo en `sign-android-release.ps1`).

Salida esperada del script firmado:
- `reports/android-packages/production-release-signed/app-release.apk`
- `reports/android-packages/production-release-signed/app-release.aab`
- Verificación posterior obligatoria (`status: "PASS"`, `signing: "signed"`):
  `npm run android:verify-release -- --output-dir reports/android-packages/production-release-signed`

---

## 4. Guardas de Escritura Comercial en Release

1. **`productionCommerceInterceptor` (`src/app/core/security/production-commerce.interceptor.ts`):**
   - Por defecto en `release`, bloquea escrituras (`POST`/`PUT`/`DELETE` salvo rutas permitidas de auth) hacia `PRODUCTION_API_BASE_URL` devolviendo HTTP `403` local (`"La operación comercial todavía no está habilitada."`).
   - Al compilar con `--enable-reviewed-commerce` (`CMC_REVIEWED_COMMERCE`), permite únicamente operaciones revisadas de carrito (`/api/cart/items`, `/api/cart/items/{id}`) y favoritos (`/api/wishlist`).
2. **`REAL_CHECKOUT_WRITE_ENABLED` (`src/app/core/repositories/checkout.repository.ts`):**
   - Permanece en `false` por defecto para evitar envíos reales accidentales de `POST /api/checkout` contra producción desde pruebas sin autorización explícita.
