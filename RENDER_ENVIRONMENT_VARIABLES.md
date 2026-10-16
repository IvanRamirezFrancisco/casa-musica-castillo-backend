# Variables de entorno para Render (backend Spring Boot)

Render: runtime **Docker** (`./Dockerfile`), rama `deploy/railway-vercel-alexa`, región **Ohio (US East)**, health check **`/actuator/health`**. Perfil `production` fijado en el Dockerfile. `PORT` lo inyecta Render.
Este documento NO contiene valores secretos. Plantilla: `render-backend.env.example`.

Leyenda — **Req**: el arranque falla si falta. **Nueva**: generar un valor nuevo. **Rotar**: el valor histórico estuvo versionado o en archivos locales; no reutilizarlo.

| Variable | Req | Origen | ¿Nueva / reutilizable? | ¿Rotar? | Función |
|---|:--:|---|---|:--:|---|
| `SPRING_DATASOURCE_URL` | Sí | Neon (connection details) | Nueva | – | JDBC `jdbc:postgresql://<HOST>/security_db?sslmode=require`. Sin fallback a localhost. |
| `SPRING_DATASOURCE_USERNAME` | Sí | Neon (role) | Nueva | – | Rol de BD. |
| `SPRING_DATASOURCE_PASSWORD` | Sí | Neon | Nueva | Sí (si coincide con la local) | Password del rol. |
| `DATABASE_URL`, `PGUSER`, `PGPASSWORD` | No | Neon | Alias | – | Fallback si falta el `SPRING_DATASOURCE_*` equivalente. |
| `APP_SECURITY_JWT_SECRET` | Sí | Generada localmente | **Nueva** | Sí | Firma de JWT. |
| `PICKUP_CODE_ENCRYPTION_KEY` | Sí | Generada localmente | **Nueva** | Sí | AES de códigos de recogida: exactamente 16/24/32 caracteres ASCII. |
| `CORS_ORIGINS` | Sí | Vercel / dominio propio | Nueva | – | Lista explícita separada por comas; sin `*`, sin `/` final. Única fuente de CORS. |
| `APP_BASE_URL` | Sí | Vercel | Nueva | – | Enlaces en correos. |
| `FRONTEND_PUBLIC_BASE_URL` | Sí | Vercel | Nueva | – | `back_urls` de Mercado Pago. |
| `BACKEND_PUBLIC_BASE_URL` | Sí | Render | Nueva | – | URL pública del backend (webhook MP). |
| `SPRING_MAIL_HOST` / `_PORT` | Sí | Brevo | Reutilizable | – | SMTP. |
| `SPRING_MAIL_USERNAME` | Sí | Brevo | Reutilizable | – | Login SMTP. |
| `SPRING_MAIL_PASSWORD` | Sí | Brevo | Reutilizable | **Sí** | Clave SMTP. |
| `APP_EMAIL_BREVO_API_KEY` | Sí | Brevo | Reutilizable | **Sí** | API v3 de Brevo. |
| `EMAIL_SENDER_NAME`, `EMAIL_SENDER_ADDRESS` | No | Brevo (remitente verificado) | Reutilizable | – | Remitente. |
| `RESEND_API_KEY`, `SENDGRID_API_KEY`, `MAILGUN_API_KEY`, `MAILGUN_DOMAIN` | No | Proveedor respectivo | – | Resend: **Sí** | Proveedores alternativos de correo. |
| `MERCADO_PAGO_ENABLED` | No | – | – | – | Habilita pagos (default producción: `false`). |
| `MERCADO_PAGO_ENVIRONMENT` | No | – | – | – | `production` / `sandbox`. |
| `MERCADO_PAGO_ACCESS_TOKEN` | Si MP activo | Mercado Pago | **Nueva (producción)** | **Sí** | Crear preferencias. |
| `MERCADO_PAGO_PUBLIC_KEY` | Si MP activo | Mercado Pago | Nueva (producción) | **Sí** | Clave pública. |
| `MERCADO_PAGO_WEBHOOK_SECRET` | Si MP activo | Mercado Pago (Webhooks) | Nueva | – | Valida HMAC `x-signature`. |
| `CLOUDINARY_CLOUD_NAME` / `_API_KEY` / `_API_SECRET` | Sí | Cloudinary | Reutilizable | Secret: recomendado | Imágenes y comprobantes (`StorageConfig` aborta si faltan). |
| `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_BUCKET` | No | Supabase | Reutilizable | Service key: **Sí** | Backups off-site. |
| `APP_NOTIFICATION_SMS_PROJECT_ID` / `_ACCESS_KEY_ID` / `_SECRET_KEY` | Sí | Sinch | Reutilizable | **Sí** | SMS (2FA). |
| `SMS_PROVIDER`, `SINCH_FROM_NUMBER` | No | Sinch | Reutilizable | – | Proveedor / número emisor. |
| `STORE_*` (8) | No | Negocio | Reutilizable | – | Datos para API pública y Alexa. |
| `JAVA_OPTS` | No | – | – | – | Sobrescribe flags JVM. |

## Notas
- Cambiar `PICKUP_CODE_ENCRYPTION_KEY` invalida el descifrado de códigos de recogida ya guardados (devuelven `null`); afecta solo a pedidos de recogida pendientes.
- `/actuator/health` en producción solo verifica la BD (el indicador de correo está desactivado para que un fallo SMTP no tumbe el servicio).
- Desarrollo local (perfil `local`): las credenciales ahora se leen de variables de entorno (mismos nombres, más `PGPASSWORD` y `FLYWAY_ADMIN_PASSWORD`). Para pruebas Maven se requiere `PGPASSWORD` (BD local).
