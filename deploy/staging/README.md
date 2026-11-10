# Aceptación Android por HTTPS

La pila ejecuta el JAR validado de `a7e027d33272e93158a6236b57ce7eff64ed2af1` con el perfil Spring `staging`. La propuesta es una VM Linux independiente con Docker Compose, DNS de staging y un certificado público de Caddy. Confirma primero en el inventario privado si existe un entorno reutilizable. El registro de despliegues de GitHub por sí solo no cubre Render.

## Preparación antes de publicar

- Usar una VM dedicada, una base nueva `castillo_staging` y un origen HTTPS distinto del comercial. El backend, PostgreSQL y SMTP se comunican por una red interna. La pasarela sirve las rutas del móvil y restringe las IP de los probadores. Permitir exclusivamente IP/CIDR concretos y actualizarlos si cambia la IP de salida del teléfono. Evitar redes abiertas, proxies delante de Caddy y certificados privados para la aceptación.
- Configurar DNS A y, solo si se probó su ruta de extremo a extremo, AAAA hacia la VM. Limitar SSH a operadores y habilitar los puertos 80 y 443 para ACME y HTTPS. Caddy conserva la IP de conexión para evaluar el acceso. Si la IP de datos móviles cambia, repetir desde una Wi-Fi autorizada o actualizar la lista mediante el operador.
- Guardar el archivo de variables de Compose fuera del repositorio con permisos exclusivos para el operador. Usar valores independientes para `STAGING_HOST`, `STAGING_ALLOWED_CIDRS` (CIDR separados por espacios), `STAGING_JAR` (ruta absoluta), `STAGING_DB_PASSWORD`, `STAGING_JWT_SECRET` (al menos 64 caracteres aleatorios) y `STAGING_PICKUP_KEY` (32 caracteres ASCII aleatorios). Los secretos quedan en el host. La APK contiene solamente el origen HTTPS.
- Verificar la huella del [artefacto de la revisión anterior](../../target/auth-system-1.0.0.jar), SHA-256 `35454ae93cf9b028e05a951e90a3b62e4ee83b7a0e6fce90de04e5649c202369`, o reconstruir desde el SHA base y registrar la huella resultante. Revisar los tags de imágenes y fijar sus digests al autorizar la VM. El perfil se monta aparte del JAR.
- Arrancar desde una sesión limpia, con el archivo externo y una cuenta operadora. Compose proporciona al contenedor únicamente las variables indicadas. Para ejecutar el JAR fuera de Compose, aislar primero el entorno y verificar `current_database()`, `current_user` e `inet_server_port()` antes de ejecutar Spring. Las variables `SPRING_DATASOURCE_*` del host tienen precedencia sobre YAML.

```sh
docker compose --env-file /ruta/privada/staging.env -f deploy/staging/compose.yml config --quiet
docker compose --env-file /ruta/privada/staging.env -f deploy/staging/compose.yml up -d
```

Validar `GET /actuator/health` desde la IP permitida y desde otra IP. Esperar 200 y 403 respectivamente. Comprobar que la API rechaza rutas administrativas, checkout, mutaciones de pedidos y webhooks. Revisar que el certificado sea válido para el DNS y que Flyway registre V52 en la base nueva. Para preparar catálogo y, después del registro, un pedido propio sintético, ejecutar el fixture desde el checkout en el host de staging.

```sh
docker compose --env-file /ruta/privada/staging.env -f deploy/staging/compose.yml exec -T postgres \
  psql -X -U castillo_staging -d castillo_staging < deploy/staging/fixtures.sql
docker compose --env-file /ruta/privada/staging.env -f deploy/staging/compose.yml exec -T postgres \
  psql -X -U castillo_staging -d castillo_staging -v fixture_username=stg-usuario < deploy/staging/fixtures.sql
```

Mailpit captura todo el correo con SMTP interno. Su interfaz queda ligada a loopback del servidor. El operador puede recuperar el mensaje desde un acceso privado al host, y entregar al probador solo el código o enlace de su cuenta de prueba por un canal acordado. El móvil acepta el enlace o el token de verificación y recuperación en su formulario. Este staging sirve una API y no aloja las páginas web de destino de los correos. Usar cuentas sintéticas que cumplan el validador de dominios, sin reutilizar identidades comerciales.

## Galaxy A55 sin USB

1. Tras verificar el origen público, compilar desde el checkout móvil autorizado con `node scripts/build-android.mjs --environment staging --variant debug --api-url $env:CMC_AUTHORIZED_STAGING_ORIGIN --isolated` en PowerShell. Verificar reporte, SHA, certificado de firma, `applicationId` y `versionCode`.
2. Transferir el APK firmado al teléfono por un canal privado autorizado y abrirlo desde el dispositivo. Para una actualización sin borrar datos deben coincidir el ID y el certificado y el código de versión debe ser compatible. Si no coinciden, usar un perfil o dispositivo separado y conservar la instalación anterior.
3. Conectar el teléfono a Internet desde la IP permitida. Registrar salud, sesión 401/200, verificación, recuperación, MFA, persistencia tras cerrar y abrir, expiración y logout. Verificar catálogo paginado, carrito, favoritos, perfil y pedidos propios con dos cuentas. Mantener los pagos y el checkout deshabilitados.
4. Al concluir, detener el staging temporal y retirar los accesos de prueba. Antes de promover a producción, cerrar OWASP y SonarCloud, validar un respaldo y el plan de migraciones contra una copia autorizada y repetir los contratos web y móvil.
