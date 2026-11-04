# Propuesta de contratos de privacidad y eliminación — Etapa 2B

Estado: **propuesta para revisión, no API implementada ni desplegada**. Base publicada `main`, SHA `46bc32d8b0417370ab35e99866cabbeaaf830bba`, consultada el 3 de noviembre de 2026. No se modifica código, configuración, base de datos ni producción.

El checkout local original contiene trabajo pendiente (HEAD `44df7468`); esta propuesta se prepara en worktree y rama independientes `feature/privacy-contract-proposal`. Los PR backend #2 (seguridad) y #3 (registro/correo) están abiertos: conciliar sus contratos antes de implementar. Sus ramas no se modifican y sus cambios no se copian aquí. El SHA de producción no se ha demostrado mediante estos archivos.

## Evidencia existente y límites

- `UserController`: `GET /api/users/profile` devuelve datos del usuario tomado de `@CurrentUser`, protegido en main por ROLE_USER; `GET /api/users/security-settings` y cambio de contraseña son contratos distintos. No hay PATCH propio ni recepción de solicitudes ARCO/eliminación.
- `UserService.updateUser/deleteUser` son métodos internos; su existencia no autoriza exponerlos como autoservicio ni borrar relaciones. Operaciones administrativas y deshabilitar cuentas no equivalen a eliminación verificada.
- `AuthController` maneja registro, login, MFA mediante contratos relacionados, verificación, logout y sesiones; `ActiveSession` registra JTI, IP, user-agent y fechas. El endpoint `/refresh` existente en main no acredita un contrato funcional de refresh para el cliente móvil; no se añade esa dependencia.
- `ShoppingCartController` y `WishlistController` manejan datos relacionados por usuario. `Order` y sus relaciones exigen evaluación de integridad/retención. Endpoints más recientes presentes en ramas locales o de integración no se consideran publicados en main.
- `SessionManagementService.cleanupExpiredSessions` programa revocación y elimina sesiones revocadas antiguas con corte de siete días en el código revisado. No constituye una política general de retención, prueba de ejecución del scheduler ni eliminación de respaldos/logs/proveedores.

El titular confirmó que responsable legal, autorización de representación, contacto ARCO y URL públicas de privacidad/eliminación están pendientes. No se define una política legal ni plazo de conservación por inferencia.

## Contratos propuestos (ninguno es consumido aún por el móvil)

| Ruta propuesta | Autorización y resultado |
|---|---|
| `GET /api/privacy/me/data` | Sesión vigente; identidad exclusivamente desde principal. Exportación filtrada/paginada del usuario, sin contraseñas, JWT, secretos TOTP, códigos de respaldo o datos ajenos. El GET de perfil actual solo cubre una parte. |
| `PATCH /api/users/me/profile` | Sesión vigente; allowlist firstName/lastName/phone; validación y control de concurrencia. No acepta id, roles, enabled, email o flags MFA. Cambiar correo necesita otro flujo de verificación. |
| `GET /api/privacy/me/preferences` | Solo finalidades opcionales previamente aprobadas; versión del aviso y preferencias vigentes. No implica consentimiento para finalidades no definidas. |
| `PATCH /api/privacy/me/preferences` | Elección explícita, default false para consentimiento opcional, retiro idempotente; recibo persistido con finalidad/versión/fecha y propagación a proveedor cuando corresponda. No usar el checkbox local de presentación como prueba legal. |
| `POST /api/privacy/me/deletion-requests` | Sesión vigente, identidad reciente/step-up acordado, confirmación explícita y clave de idempotencia. Devuelve 202 con recibo persistido, no afirma eliminación ejecutada. |
| `GET /api/privacy/me/requests/{requestId}` | Consulta por principal + requestId; 404 neutral para solicitudes ajenas/inexistentes. Estados describen recepción, verificación y procesamiento comprobado. |
| `POST /api/public/privacy/deletion-requests` | Soporte del futuro recurso web externo aprobado. Respuesta neutral para correos existentes/no existentes; controles antiabuso y verificación de titularidad antes de tramitar. Ninguna cuenta se borra por conocer su email. |

Ejemplo de respuesta **propuesta**, solo después de confirmar la transacción que guarda la solicitud:

```json
{
  "success": true,
  "data": {
    "requestId": "opaque-reference",
    "status": "RECEIVED",
    "receivedAt": "server-generated-timestamp",
    "message": "Recibimos tu solicitud. La recepción no confirma su ejecución."
  }
}
```

El identificador debe ser opaco e impredecible. Sin un registro persistido no se devuelve un recibo de éxito. Correo fallido no debe perder la solicitud recibida: usar outbox/reintento controlado y expresar el estado real. No devolver excepciones SQL ni datos personales en errores. El frontend no debe inferir eliminación de un 202 ni de logout.

## Identidad, solicitudes externas y errores

La app autenticada obtiene el sujeto del principal; ningún campo userId/email del body puede seleccionar a otra persona. Confirmación sensible accesible y explícita; reautenticación/MFA con prueba corta ligada a usuario, sesión, propósito y caducidad, consumida una vez. El mecanismo de step-up aún debe acordarse e implementarse: no se presume que una contraseña en una vista o un JWT antiguo lo sustituyan.

El recurso web público no tiene URL aprobada. Debe explicar qué puede solicitarse, verificación, recibo y estados/retenciones aprobadas sin exigir reinstalar la app. Para solicitudes no autenticadas, una respuesta neutral no revela existencia de cuentas; el proceso posterior verifica identidad mediante un canal confirmado. Evitar documentos de identidad por defecto. Guardar hashes de desafíos, limitar intentos, auditar sin secretos, aplicar rate limit y protección CSRF donde se utilicen cookies. Acceso autenticado Bearer y formulario web pueden necesitar controles diferentes.

401 termina sesión; 403 niega una operación sin modificar datos; 404 no enumera solicitudes ajenas; 409/412 representa concurrencia/estado, 422 validación neutral y 429 limitación. 5xx no se trata como recibo. Las idempotencias de reintento no deben generar solicitudes repetidas ni permitir consultar las de terceros.

## Plan de implementación independiente y criterios de aceptación

1. Confirmar responsable/representación/contacto/finalidades, alcance de cancelación/oposición, retención por categoría y respuesta a respaldos/proveedores. Documentar las decisiones sin inventar plazos legales. Conciliar PR #2/#3 y verificar la rama backend elegida con sus responsables.
2. Preparar PostgreSQL aislado, rol mínimo, datos sintéticos, perfil Spring existente con overrides seguros y SMTP de captura explícito; pagos deshabilitados. Verificar que URL/base/usuario y correo no sean comerciales. No usar tests H2 como prueba PostgreSQL real.
3. Mapear FKs/cascadas y datos por usuario: identidad, roles, MFA/recuperación, sesiones/JTI, carrito/favoritos, pedidos/pagos/comprobantes, opiniones, auditoría, archivos y copias. Las decisiones de borrar, anonimizar, restringir o conservar deben aprobarse por categoría; no eliminar en cascada sin esa matriz.
4. Añadir migraciones versionadas para solicitudes y evidencia mínima, DTO allowlist, repositorios, servicio transaccional y autorización por sujeto. Revisión de Threat Model: IDOR, mass assignment, repetición, abuso, enumeración y fallos parciales. No implementar un borrado masivo ni DELETE físico inmediato como solución abreviada.
5. Implementar recepción persistente, verificación, recibo y consulta propia antes del procesador de eliminación. Separar la operación comercial de usuario, las facultades administrativas y el workflow de derechos. Aprobar el procedimiento humano/automatizado y mensajes sobre datos conservados.
6. Ejecutar procesamiento únicamente cuando la identidad y reglas aprobadas lo permitan. Revocar sesiones/códigos correspondientes, impedir nuevos accesos, coordinar datos y proveedores, verificar resultado y reflejarlo sin falsos estados COMPLETED. Diseñar recuperación/reintentos y retención de evidencia mínima sin conservar datos innecesarios.
7. Crear recurso web revisado y accesible desde URL pública real; probar desde fuera de la app con identidad sintética. Integrar móvil por Repository/Service solo tras contrato aprobado y desplegado en staging; no añadir fallback DEMO ante errores.
8. Pruebas: propio/ajeno, usuario deshabilitado, expiración/401, MFA, confirmación negativa, CSRF cuando aplique, concurrencia, idempotencia, email inexistente, SMTP caído, outbox, rollback, integridad de pedidos y datos conservados, descarga sin secretos, revocación y protección de logs. E2E PostgreSQL aislado con evidencia separada de mocks.

No marcar ARCO/eliminación/TT-05/Play como Done por aceptar esta propuesta. Cierre requiere contrato funcionando, operación y responsables verificados, URL pública, pruebas y revisión. El móvil de Etapa 2B solo consulta su perfil actual y ofrece preferencia local de ocultación; no realiza solicitudes ni simula borrado.
