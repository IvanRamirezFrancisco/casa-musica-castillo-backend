# Eliminación de Cuenta y Política de Retención de Datos — Casa de Música Castillo

**Aplicación / Plataforma:** Casa de Música Castillo (`com.casademusicacastillo.app` en Android y `https://casa-musica-castillo.vercel.app` en web).  
**Ruta pública de solicitud sin sesión:** `https://casa-musica-castillo.vercel.app/eliminar-cuenta`  
**Versión de política:** `2026-04`

---

## 1. Canales Disponibles para Solicitar la Eliminación

1. **Solicitud pública sin sesión (`/eliminar-cuenta`, web o enlace desde Android):**
   - `GET /api/public/legal/deletion`: verifica disponibilidad del servicio y parámetros de vigencia.
   - `POST /api/public/legal/deletion/request`: recibe el correo electrónico, aplica límite de frecuencia por IP y correo y devuelve una respuesta uniforme `202 Accepted` sin revelar si la cuenta existe (protección anti-enumeración). Si la cuenta existe, genera un token aleatorio de un solo uso con vigencia de 30 minutos (almacenando únicamente su hash SHA-256 en `auth.account_deletion_tokens`) y envía un enlace seguro por correo vía Brevo HTTPS.
   - `POST /api/public/legal/deletion/confirm`: valida el token no expirado y no usado, requiere confirmación explícita del titular (`confirmed = true`), revoca sesiones activas y refresh tokens y procesa o registra la solicitud de eliminación devolviendo un folio `requestId` UUID.
2. **Solicitud autenticada desde la aplicación web o Android:**
   - `POST /api/users/me/deletion-requests`: requiere sesión activa, verificación reforzada de identidad (`currentPassword` válido) y confirmación explícita.
   - `GET /api/users/me/deletion-requests`: consulta el recibo del usuario autenticado.
3. **Consulta protegida de estatus por folio:**
   - `POST /api/public/legal/deletion/status`: consulta anónima del estado (`RECEIVED`, `REVIEW_REQUIRED`, `COMPLETED`) mediante el `requestId` UUID sin exponer correo ni datos personales.
4. **Resolución administrativa controlada:**
   - `GET /api/admin/account-deletion-requests` y `POST /api/admin/account-deletion-requests/{requestId}/approve` (`ROLE_ADMIN`): permiten resolver solicitudes en `REVIEW_REQUIRED` únicamente cuando no existen pedidos activos ni pagos pendientes, desvinculando y anonimizando registros históricos antes de eliminar definitivamente la cuenta.

---

## 2. Clasificación Definitiva de Datos al Completar la Eliminación

| Categoría / Tabla | Tratamiento al completar (`COMPLETED`) | Base / Justificación |
| :--- | :--- | :--- |
| Perfil y credenciales (`auth.users`, `auth.user_roles`, `auth.verification_tokens`, `security.password_reset_tokens`) | **Eliminación definitiva** | Cumplimiento LFPDPPP / Google Play Account Deletion |
| Factores 2FA y códigos de respaldo (`auth.two_factor_tokens`, `auth.backup_codes`, secretos TOTP) | **Eliminación definitiva** | Sin uso operativo tras cierre de cuenta |
| Sesiones y tokens (`auth.active_sessions`, `auth.refresh_tokens`, `auth.login_challenges`, `auth.login_factor_limits`, `auth.account_deletion_tokens`) | **Eliminación inmediata / definitiva** | Revocación de acceso en todos los dispositivos |
| Direcciones, carrito, favoritos y reseñas (`customer.addresses`, `sales.shopping_carts`, `sales.cart_items`, `sales.wishlists`, `catalog.product_reviews`, `catalog.review_helpfulness`) | **Eliminación definitiva** | Datos personales y de preferencia no sujetos a retención fiscal |
| Intentos de acceso/recuperación (`security.login_attempts`, `security.password_recovery_attempts`) | **Eliminación definitiva** | Minimización de datos por correo |
| Pedidos concluidos y cupones usados (`sales.orders`, `sales.coupon_usage`) | **Conservación fiscal desvinculada y anonimizada** (`user_id = NULL`, dirección/notas reemplazadas por marcador anónimo, conservando folio, importes, IVA y estatus) | Art. 30 del Código Fiscal de la Federación (conservación contable/fiscal hasta 5 años) |
| Comprobantes y pagos (`sales.payment_proofs`, `sales.payments`) | Bloques activos impiden cierre (`409 Conflict`); registros históricos de pago se conservan desvinculados (`created_by_user_id = NULL`) para conciliación fiscal | CFF y auditoría financiera |
| Trazabilidad de seguridad (`security.audit_logs`) | **Anonimización / desvinculación** (`performed_by_user_id = NULL`, redacción de payloads IP/User-Agent) al aprobar revisión | Continuidad de bitácora sin PII identificable |
| Aceptaciones legales y recibo de baja (`auth.registration_legal_acceptances`, `auth.account_deletion_requests`) | `registration_legal_acceptances` se elimina en cascada; `account_deletion_requests` conserva únicamente `request_id`, canal, fechas y `status = 'COMPLETED'` con `user_id = NULL` | Evidencia verificable de cumplimiento |

---

## 3. Pedidos Activos, Terceros y Respaldos

- **Pedidos en curso o comprobantes pendientes (`PENDING`, `CONFIRMED`, `PROCESSING`):** Nunca se cancelan pagos ni entregas de forma automática ni se borra la cuenta mientras existan operaciones comerciales activas. La solicitud queda en `REVIEW_REQUIRED` con motivo `ACTIVE_ORDERS_OR_PENDING_PAYMENTS` y el cierre administrativo permanece bloqueado (`409 Conflict`) hasta la entrega, cancelación o reembolso definitivo.
- **Proveedores externos (Brevo, Cloudinary, Mercado Pago):** Brevo conserva logs transaccionales de entrega de correo conforme a su retención operativa; Cloudinary y Mercado Pago mantienen referencias técnicas de comprobantes/transacciones conciliadas que pueden requerir depuración manual o retención regulatoria financiera.
- **Respaldos de base de datos:** Las copias de seguridad cifradas en bóveda siguen un ciclo rotatorio programado y no restauran cuentas ya eliminadas en el entorno productivo activo.
- **Pérdida de acceso al correo:** El titular puede acudir al Centro de Ayuda (`/ayuda`) o escribir a `soporte@casamusicacastillo.com` indicando su nombre completo y número de pedido para validación manual de identidad.
