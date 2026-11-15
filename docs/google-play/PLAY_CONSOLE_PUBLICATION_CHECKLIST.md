# Play Console Publication Checklist — Casa de Música Castillo

> Fecha de verificación: `2026-11-15`  
> Paquete Android: `com.casademusicacastillo.app`  
> Target SDK verificado: **API 36** (`versionCode 2`, `versionName 1.1`)  
> Política operativa: Preparación completa hasta pista cerrada; **nunca publicar a producción abierta** sin aprobación explícita.

---

## 1. Artefactos Técnicos y Firma (Play App Signing)

- [x] `applicationId` fijo en `com.casademusicacastillo.app`.
- [x] `targetSdk` actualizado a **36** y `minSdk` en **24**, verificados en AAB (`assembleRelease` + `bundleRelease` + `verify-android-release.py`).
- [x] `debuggable=false`, `allowBackup=false`, `usesCleartextTraffic=false` (`network_security_config.xml`).
- [x] Backend apuntando a Railway HTTPS (`https://casa-musica-castillo-backend-production.up.railway.app`) con `ASSETS_ONRENDER_COUNT=0`.
- [x] Certificados R8 generados (`r8BridgeMethodPreserved=true`, `r8SecureStorageMethodsPreserved=true`, `sourceMaps=false`).
- [ ] **Ejecutar firma interactiva de AAB y APK** con `C:\Users\ivanf\CMC-Keys\castillo-upload.p12` mediante `.\scripts\sign-android-release.ps1` (requiere contraseña privada del usuario).
- [ ] Subir el archivo de mapeo R8 (`mapping.txt` generado en `android/app/build/outputs/mapping/release/`) junto con el `.aab` firmado a Play Console para desofuscación de ANRs/crashes.
- [ ] Conservar `castillo-upload.p12` y `castillo-upload-cert.pem` fuera del repositorio Git y verificar en Play Console **App Integrity -> Play App Signing** la huella SHA-256 de la upload key (`47:49:2A:A3:5B:3F:B2:61:73:BA:F1:EF:23:6C:3C:2B:EF:C3:3D:B8:E3:ED:C3:D5:2E:F0:C7:75:35:33:DB:C3`).

---

## 2. Requisito de Pruebas Cerradas para Cuentas Personales (12 Testers / 14 Días)

Si la cuenta de Google Play Developer es de tipo **Personal** creada recientemente:
- [ ] Subir primero el `.aab` firmado a **Testing -> Closed testing** (NO Production).
- [ ] Configurar lista de al menos **12 testers** con cuentas Google activas y compartir el enlace de opt-in en Play Store.
- [ ] Confirmar que al menos **12 testers** aceptan la invitación y mantienen la prueba **de forma continua durante mínimo 14 días consecutivos**.
- [ ] Instalar y probar la app desde el canal cerrado en dispositivos reales (incluido **Samsung Galaxy A55** pendiente de aceptación física) cubriendo catálogo, login/MFA, centro de ayuda y eliminación de cuenta.

---

## 3. Declaraciones de Cumplimiento y Contenido en Play Console

| Sección Play Console | Estado / Valor requerido basado en el código real |
|---|---|
| **App access (Acceso con credenciales)** | La app permite navegación pública sin cuenta (Inicio, Catálogo, Detalle, Ayuda, Políticas). Si el revisor de Google Play evalúa flujo autenticado, proporcionar credenciales de una cuenta de pruebas dedicada (sin MFA bloqueante o con código TOTP/instrucciones) en *App content -> App access* |
| **Privacy Policy URL** | Configurar URL pública HTTPS en producción (por ejemplo `https://casa-musica-castillo.vercel.app/...` o página legal pública servida por el frontend / `/api/public/legal/registration`) y confirmar HTTP 200 |
| **Account deletion (Eliminación de cuenta)** | Soportada en app y respaldada por backend V56 (`/api/public/legal/deletion`). Declarar la URL web de eliminación de cuenta exigida por Google Play en *Data safety* |
| **Data safety** | Llenar exactamente conforme a [`DATA_SAFETY_ASSESSMENT.md`](./DATA_SAFETY_ASSESSMENT.md) (TLS en tránsito, almacenamiento seguro cifrado para sesión, sin Advertising ID, sin geolocalización/cámara/contactos) |
| **Ads (Anuncios)** | **No**, la aplicación no integra SDKs de publicidad ni muestra anuncios (`No` a "¿Contiene anuncios?") |
| **Target audience (Público objetivo)** | **18+ / Solo adultos** (tienda de comercio electrónico, facturación, compras y envíos físicos; no dirigida a niños) |
| **Content rating (IARC)** | Completar cuestionario en Play Console declarando aplicación comercial / catálogo e-commerce sin contenido violento ni juego |
| **Financial features / Bienes físicos** | Venta de **bienes físicos** (instrumentos y accesorios musicales de Casa de Música Castillo, Huejutla de Reyes) pagados mediante transferencia bancaria o Mercado Pago (bienes físicos están exentos de Google Play Billing) |
| **News / COVID / Government / Health apps** | Seleccionar **No** en todas las declaraciones especiales aplicables |

---

## 4. Ficha de Google Play Store (Store Listing)

> Base verificada en `reports/android-packages/production-release/FICHA-PLAY-CANDIDATA.md`. Ajustar recursos gráficos definitivos antes de enviar a revisión.

- **Nombre de la app:** `Casa de Música Castillo`
- **Descripción breve (<= 80 caracteres):** `Catálogo de instrumentos musicales, carrito y pedidos de Casa Castillo.`
- **Categoría sugerida:** `Compras` (`Shopping`)
- **Idioma principal:** Español (México / Latinoamérica) (`es-MX` / `es-419`)
- **Activos gráficos requeridos en Play Console:**
  - Icono de alta resolución (`512 x 512 px` PNG 32-bit sin alfa).
  - Gráfico de funciones / Feature graphic (`1024 x 500 px` PNG o JPEG).
  - Capturas de teléfono (`>= 2` capturas reales de la versión conectada a Railway; existen capturas previas de flujo en `docs/testing/commerce-ui/` como referencia, tomar capturas limpias post-aceptación en dispositivo físico).
- **Correo y sitio de contacto del desarrollador:** Verificar que el email de soporte configurado en Play Console sea real y monitoreado (Huejutla de Reyes, Hgo.).

---

## 5. Compuerta Final Antes de Enviar a Revisión

1. Firmware/dispositivo real probado: **Galaxy A55** conectado vía ADB / Play Internal o Closed Testing track con flujo completo sobre Railway.
2. En el `Android` AAB candidato final a distribución comercial, decidir expresamente si se compila con `-EnableReviewedCommerce` para habilitar escritura revisada de carrito/favoritos y probar en vivo el comportamiento completo con cuenta de prueba.
3. Verificar que `vercel.app` está sirviendo el commit actualizado (`deploy/vercel-frontend` tras merge de PR #63) y que Railway tiene `APP_JOBS_BACKUP_DATABASE_ENABLED=false`, `APP_JOBS_INVENTORY_AUDIT_ENABLED=false` (solo después de verificar Neon History window V56) y `APP_MERCADOPAGO_RECONCILIATION_ENABLED=true`.
