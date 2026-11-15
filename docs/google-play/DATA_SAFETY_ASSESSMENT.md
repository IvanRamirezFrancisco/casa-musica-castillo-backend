# Evaluación de Seguridad de Datos (Google Play Data Safety) — Casa de Música Castillo

> Fecha de auditoría de código: `2026-11-15`  
> Paquete evaluado: `com.casademusicacastillo.app` (`targetSdk 36`)  
> Base de evidencia: Inspección directa de `AndroidManifest.xml`, `network_security_config.xml`, `capacitor.config.ts`, `verification.json`, servicios Angular/Ionic y backend Spring Boot V56.

---

## 1. Postura Técnica de Red, Permisos y Almacenamiento Local

| Aspecto verificado | Implementación real en el código | Evidencia |
|---|---|---|
| **Permisos del manifiesto** | Únicamente `android.permission.INTERNET`, `android.permission.VIBRATE` (haptics Capacitor) y `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `verification.json` & `AndroidManifest.xml` |
| **Permisos sensibles NO solicitados** | **NO** solicita cámara, micrófono, ubicación GPS/precisa/aproximada, contactos, calendario, almacenamiento externo legacy ni lectura de SMS/teléfono | Verificado en `permissions` de `verification.json` |
| **Cifrado en tránsito** | Todas las llamadas en producción usan **HTTPS/TLS** hacia `https://casa-musica-castillo-backend-production.up.railway.app`; `android:usesCleartextTraffic="false"` y `<base-config cleartextTrafficPermitted="false" />` bloquean HTTP plano | `AndroidManifest.xml`, `network_security_config.xml` |
| **Backup automático de sistema Android** | `android:allowBackup="false"` impide extracción automática vía ADB/cloud backup | `AndroidManifest.xml` & `verification.json` |
| **Almacenamiento seguro local** | Plugin `@aparajita/capacitor-secure-storage@8.0.1` (Android Keystore) para persistencia segura de sesión cuando corresponde | `build-android` plugins sync & `r8SecureStorageMethodsPreserved: true` |
| **SDKs de anuncios / analítica de terceros** | **Ninguno** integrado en el binario móvil | Sin `AD_ID` ni Firebase Analytics/AdMob |

---

## 2. Matriz de Datos Recopilados y Tratados (Para Formulario Data Safety)

| Categoría Play Console | Tipo de dato específico | ¿Se recopila? | ¿Se comparte con terceros? | Efímero / Encriptado en tránsito | Finalidad real verificada en código |
|---|---|---|---|---|---|
| **Personal info** | Nombre y apellidos | **Sí** (en registro/perfil y envíos) | No para marketing; solo logística si aplica envío físico | Sí (HTTPS) / No efímero (requerido para cuenta y pedidos) | Gestión de cuenta y entrega de pedidos físicos |
| **Personal info** | Dirección de correo electrónico | **Sí** | Proveedor transaccional **Brevo** (solo envío de verificación/MFA/pedidos) | Sí (HTTPS) | Creación de cuenta, autenticación, verificación 2FA/MFA, recuperación de contraseña y notificaciones de pedidos |
| **Personal info** | ID de usuario interno | **Sí** | No | Sí (HTTPS) | Autenticación, correlación de carrito, favoritos y pedidos (`/api/users/profile`, `/api/orders`) |
| **Personal info** | Dirección física | **Sí** (si el usuario captura domicilio para envío) | No para fines publicitarios | Sí (HTTPS) | Cotización y envío físico de instrumentos/productos |
| **Personal info** | Número telefónico | **Sí** (opcional en perfil / obligatorio solo en dirección de envío físico) | No | Sí (HTTPS) | Contacto de entrega y actualización de perfil (`/api/users/profile`) |
| **Financial info** | Historial de compras | **Sí** | **Mercado Pago** (cuando el usuario elige checkout MP Sandbox/prod para bienes físicos) | Sí (HTTPS) | Creación y consulta de pedidos (`/api/orders`) y conciliación de pagos |
| **Photos and videos** | Imágenes seleccionadas por usuario | **Condicional** (solo si se adjunta comprobante de transferencia bancaria o reseña/imagen de perfil) | **Cloudinary** (almacenamiento multimedia HTTPS del backend) | Sí (HTTPS) | Verificación manual de comprobantes de transferencia bancaria |
| **App activity / App info** | Solicitudes API / sesiones / estado 2FA | **Sí** | No | Sí (HTTPS) | Seguridad de sesión, prevención de abuso/rate limit y soporte técnico |
| **Location / Contacts / Messages / Audio / Device IDs publicitarios** | GPS, libreta, SMS, Advertising ID | **No** | **No** | N/A | No solicitados ni recolectados por la app |

---

## 3. Controles del Usuario en Data Safety

1. **¿Los datos se cifran en tránsito?**  
   **Sí** (`TLS / HTTPS` obligatorio; `cleartextTrafficPermitted="false"` en Android Release).
2. **¿Los usuarios pueden solicitar la eliminación de su cuenta y datos?**  
   **Sí.** El backend (Flyway V55/V56 `track account deletion outcomes`) expone reglas de retención/eliminación (`/api/public/legal/deletion`) y gestión de cuenta autenticada, y la app móvil / web ofrecen flujo de solicitud de eliminación. Declarar en Play Console la URL web accesible sin requerir la app instalada.
3. **Recopilación opcional vs. obligatoria:**  
   - **Obligatoria al crear cuenta:** correo electrónico, nombre, identificador de sesión y contraseña (almacenada con hash en backend).  
   - **Opcional al navegar catálogo público:** ninguna (el catálogo, detalle de producto y centro de ayuda son públicos sin registro).  
   - **Opcional / bajo demanda del usuario:** teléfono/dirección (solo si agrega domicilio o completa perfil), TOTP MFA opcional y comprobante de pago (solo al pagar pedido por transferencia).
4. **Regla de no sobre-declarar:**  
   - En los envíos donde el usuario elige recoger en tienda y no captura envío, no exigir domicilio; sin embargo, como la funcionalidad existe dentro de checkout/direcciones, Google Play exige declarar **Physical address** y **Phone number** como datos que la app *puede* recopilar.
   - Si no se habilita subida directa de imágenes en la versión cerrada inicial móvil, confirmar antes de enviar la ficha si el comprobante de transferencia está activo en la pantalla de detalle de pedido móvil para declarar **Photos** con exactitud factual.
