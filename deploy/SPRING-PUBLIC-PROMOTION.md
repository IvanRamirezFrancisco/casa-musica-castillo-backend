# Promoción con Spring público

La rama `security/spring-public-runtime` parte de `85415b176a3d4591146acb9649f6655fd457b6b7`. Mantiene V51–V53 sin cambios. Spring Boot 4.0.9 gestiona Spring Framework 7.0.10, Spring Security 7.0.8 y Flyway 11.14.1. Se conserva Jackson 2 como convertidor de la API para mantener los DTO existentes y la integración de los servicios que lo inyectan.

La autenticación sigue usando BCrypt y la comprobación de 72 bytes. El filtro JWT pertenece a la cadena de seguridad. El contenedor de servlets omite un segundo registro del mismo filtro. Las pruebas de Security 7 cargan el soporte de MockMvc y conservan las mismas aserciones de autorización.

Para reproducir la regresión en CI, ejecutar `mvn -B --no-transfer-progress package` con los PostgreSQL desechables definidos en `.github/workflows/build.yml`. Para OWASP, ejecutar `mvn -B --no-transfer-progress org.owasp:dependency-check-maven:check`. La configuración CI restaura una caché de NVD, exige actualizarla y conserva `failOnError=true` y el umbral CVSS 7. Sonar usa el JDK 21 del runner para evitar el aprovisionamiento remoto de JRE. El token del repositorio debe conservar permiso de análisis en el proyecto de SonarCloud.

En staging, validar con cuentas sintéticas el contrato de login, `preAuthChallenge`, reanudación de sesión, autorización de carrito y pedidos y sincronización web/Android. Comparar el historial Flyway y ensayar arranque con 512 MiB y 0.1 CPU. La revisión antigua en Render permitía acceso anónimo a `/api/auth/**`. El argumento Authorization obligatorio de `/api/auth/me` fallaba antes de entrar al método y el manejador genérico lo convertía en 500. El candidato V53 protege la ruta en Spring Security y devuelve 401. El build QA de Android contra producción conserva el bloqueo explícito de credenciales.

Mantener el PR de promoción en borrador hasta verificar el candidato exacto, acceso a Sonar, estado de Render y Neon, respaldo restaurable, arranque con los recursos seleccionados y transición de la web en Vercel. La ventana y las escrituras productivas requieren autorización expresa.
