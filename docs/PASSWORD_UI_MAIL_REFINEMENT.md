# Refinamiento de correo y aceptación de contraseña

Continuación visual y de contrato sobre V58/V59, sin alterar sus garantías ni migraciones. Rama `fix/account-activation-hardening`, HEAD `30a8c5ab9c85d58fe998935d7725edf3d2d3ba2f`, con todo el trabajo local previo conservado.

## Cambios

- `src/main/java/com/security/service/AuthMailContent.java`: frame transaccional de tablas y CSS inline, máximo 600 px y fallback MSO, para notificación de cambio y correo de recuperación. Marca en texto, sin recursos remotos esenciales. Valores dinámicos escapados, HTML y alternativa plain. La notificación no incluye un token ni crea enlaces; no se inventan contactos de soporte.
- `src/main/java/com/security/service/EmailService.java`: ambos caminos de recuperación reutilizan esa composición. Se conserva transporte, momento de envío, remitente configurado, asunto de la candidata y clasificación de resultados. SMTP/Mailpit y Brevo comparten contenido.
- `src/test/java/com/security/service/AuthMailContentTest.java`: propósito, escape, ausencia de secretos/recursos externos, enlace solo en recuperación y equivalencia plain.
- `src/test/java/com/security/service/EmailServiceTransportTest.java`: contrato positivo Brevo y multipart UTF-8 SMTP, además de los fallos de proveedor ya existentes.

No se modificaron AuthService, sesión, JWT, MFA, validación BCrypt, límites compartidos, locks, cola persistente ni políticas de entrega/reintento. Activación y MFA conservan sus plantillas. No hay nueva dependencia.

## Resultados

`mvn -B --no-transfer-progress -o package`: **223 pruebas**, cero fallos, errores u omitidas; **3 min 53 s**. Línea base 219; se añaden cuatro pruebas. Incluye PostgreSQL real, migraciones limpias y desde versión anterior, concurrencia, revocación, SMTP y MFA. Los errores de transporte Brevo se simulan; no son envíos productivos. No se usó skipTests ni se relajaron restricciones.

JAR SHA-256: `104e37eceb232426cbabdc22c3c25991bce91aa6b7aa1be506a14fff02466be0`.

Aceptación de navegador con Spring Boot local 18083 y PostgreSQL 18 aislado 55439/base `password_e2e_test`: éxito de recuperación con navegación automática/manual segura, HTTP 200 de capacidad real, cambio autenticado, JWT A/B con 401 y nuevo Login. Mailpit recibió dos notificaciones para la cuenta A, una por cada cambio confirmado; los errores y reutilización de enlace no generaron una notificación extra. Se preservan las pruebas MFA de la suite; este refinamiento no constituye un recorrido físico Android.

El MIME real de Mailpit confirmó Subject español, MIME 1.0, multipart con `text/plain` y `text/html`, ambos UTF-8. El HTML real fue renderizado en el iframe de Mailpit y a 320 CSS px en Chrome. No se atribuye esa verificación a Gmail/Outlook reales. El asunto permanece «Se actualizó la contraseña de tu cuenta — Casa de Música Castillo».

## Discrepancia de entorno

La JVM histórica PID 5516 de 8080 ejecuta desde la carpeta padre `segundo proyecto`, rama `codex/integrate-brevo-original`, HEAD `44df7468f65180e1aebda161eabe66d94355d95d` con cambios locales; su `UserController.class` no declara la capacidad. El Angular activo de 4200 usa el origen predeterminado 8080. No se detuvieron esos procesos ni se escribió en su base de datos. No se inspeccionó la sesión del propietario.

La candidata de este checkout sí devolvió, con sesión sintética autorizada:

```json
{"status":200,"success":true,"data":{"passwordChangeRevokesSessions":true}}
```

Angular ahora exige boolean explícito; una propiedad ausente provoca error de contrato recuperable. No se habilita por suposición ni se convierte un 401 en falta de soporte.

Se deja un entorno temporal de revisión en frontend `http://127.0.0.1:14200` y API `http://127.0.0.1:18083`, base nueva `password_refinement_dev` de este mismo clúster desechable. Máximo 60 minutos desde el arranque; parada mediante archivo ignorado `target/password-refinement-runtime.stop`. El runner termina solo sus procesos y clústeres; Mailpit y servidores históricos no se tocan. Los scripts de aceptación en target/dist están ignorados y no forman parte del commit.

## Evidencia y promoción

El informe completo, lista frontend, accesibilidad y resultados móviles están en `docs/testing/PASSWORD_REFINEMENT.md` del repositorio móvil. Galería/plantillas/JSON sanitizados: `C:/Users/ivanf/.codex/visualizations/2026/11/16/01a1e6c5-73a1-7da1-999b-2b1ab6c10033/password-refinement-evidence.html`.

Frontend final: 81 específicas, integración 265/265, regresión 277/277, completa 906/907 (único fallo previo de búsqueda compacta), lint y builds aprobados, build-config 9/9. No se modificó ese fallo ni su prueba. Persisten advertencias SCSS anteriores, sin ampliar presupuestos.

Sin commit, push, merge, despliegue o modificación de main/develop. Production/Brevo/Railway/base comercial/Google Play intactos. Falta la futura revisión y promoción autorizada, smoke Brevo staging y aceptación Android/cliente de correo real. No se da por resuelto el riesgo temporal de antienumeración previo con esta intervención visual.
