# Plan de Rollback Verificable hacia Render — Casa de Música Castillo

> Fecha de elaboración: `2026-11-15`  
> Objetivo: Garantizar retorno seguro y sin pérdida de datos desde Railway hacia `https://casa-musica-castillo-backend.onrender.com` en caso de incidente grave durante la aceptación.

---

## 1. Principios de Seguridad del Rollback

1. **Base de datos compartida en Neon:** Dado que Railway y Render apuntan a la misma base Neon (Flyway **V56**), **no existe divergencia de esquema ni migración de datos** al revertir tráfico HTTP mientras el código en Render permanezca compatible con Flyway V56 (`chore/backend-stability-audit` o commit equivalente ya probado en Render).
2. **Regla estricta de escritor único:** Antes de habilitar Render como destino activo de tráfico y jobs, desactivar la conciliación/jobs en Railway (o pausar el servicio Railway) para evitar doble ejecución simultánea contra Neon.
3. **No destruir respaldos ni cambiar `ops.system_automations` globalmente:** El control de instancia se realiza por variables de entorno (`APP_MERCADOPAGO_RECONCILIATION_ENABLED`, `APP_JOBS_*`).

---

## 2. Condiciones de Activación (Cuándo ejecutar Rollback)

Ejecutar este procedimiento si tras el cutover se presenta alguno de los siguientes eventos sin mitigación inmediata:
- Indisponibilidad prolongada de Railway (`HTTP 5xx` o caída de `/actuator/health`).
- Fallos recurrentes de webhooks/conciliación de Mercado Pago, envío HTTPS Brevo, subida a Cloudinary o Supabase Storage exclusivos de Railway.
- Regresión crítica detectada en pruebas de aceptación Web (Vercel) o Android sin solución rápida en Railway.

---

## 3. Procedimiento Paso a Paso (< 10 minutos para Web + Backend)

### Paso 1 — Verificar disponibilidad y versión del backend Render
```powershell
Invoke-WebRequest -Uri "https://casa-musica-castillo-backend.onrender.com/actuator/health" -TimeoutSec 60 -UseBasicParsing
```
- Nota: Si Render estaba inactivo/suspendido, esperar el arranque completo hasta confirmar `{"status":"UP"}` y revisar en el panel de Render que el deploy activo sea compatible con el esquema **V56** actual en Neon.

### Paso 2 — Conmutar escritor único de jobs (Railway -> Render)
1. En **Railway**:
   - Establecer temporalmente `APP_MERCADOPAGO_RECONCILIATION_ENABLED=false` (o pausar/detener el deployment activo en Railway).
2. En **Render**:
   - Verificar que `APP_MERCADOPAGO_RECONCILIATION_ENABLED=true` (o sin sobrescribir en `false`), y que las credenciales de Neon, JWT, Brevo, Cloudinary, Supabase y Mercado Pago coincidan con producción.
   - Si no se requiere el respaldo periódico en Render, aplicar allí también `APP_JOBS_BACKUP_DATABASE_ENABLED=false` y `APP_JOBS_INVENTORY_AUDIT_ENABLED=false`.

### Paso 3 — Revertir Webhooks externos (si apuntaban a Railway)
- **Mercado Pago Webhooks / IPN:** Actualizar la URL de notificación de regreso a `https://casa-musica-castillo-backend.onrender.com/api/webhooks/mercado-pago`.

### Paso 4 — Revertir Frontend Angular (Vercel)
En el repositorio frontend (`deploy/vercel-frontend` / PR de rollback o commit de reversión):
1. En `src/environments/environment.prod.ts`:
   ```ts
   apiUrl: 'https://casa-musica-castillo-backend.onrender.com/api',
   ```
2. En `ngsw-config.json`:
   - Reemplazar `https://casa-musica-castillo-backend-production.up.railway.app` por `https://casa-musica-castillo-backend.onrender.com` en los `dataGroups` (`public-home-products`, `public-product-catalog`, `public-product-detail`, `public-categories`, `public-help-center`).
3. Ejecutar `npm run build` y redesplegar en Vercel (o promover el deployment anterior sano directamente en el dashboard de Vercel para recuperación en < 1 minuto).

### Paso 5 — Rollback en Android (si ya se distribuyó APK/AAB apuntando a Railway)
- Para pruebas internas / distribución controlada aún no publicada masivamente:
  1. En `src/environments/environment-settings.ts`, revertir `PRODUCTION_API_BASE_URL` a `'https://casa-musica-castillo-backend.onrender.com'`.
  2. Incrementar `versionCode` en `android/app/build.gradle` si se distribuye nueva compilación firmada en Google Play Console (no se permite reutilizar un `versionCode` ya subido a Play).
  3. Recompilar con `npm run android:release`, verificar con `npm run android:verify-release` y firmar con `castillo-upload.p12`.

---

## 4. Verificación Post-Rollback

1. `GET https://casa-musica-castillo-backend.onrender.com/actuator/health` -> `200 UP`.
2. `GET https://casa-musica-castillo-backend.onrender.com/api/public/products/latest` con `Origin: https://casa-musica-castillo.vercel.app` -> `200` y cabecera `Access-Control-Allow-Origin` presente.
3. Iniciar sesión web con cuenta de prueba verificada (MFA/TOTP si aplica) y confirmar lectura de perfil/carrito sin errores de CORS ni 502.
4. Confirmar en logs de Railway que no se están ejecutando escrituras concurrentes de reconciliación o expiración.
