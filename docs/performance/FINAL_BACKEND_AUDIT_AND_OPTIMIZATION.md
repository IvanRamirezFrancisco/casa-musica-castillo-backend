# Auditoría final del backend y optimización

## Estado y evidencias

Backend `feature/email-activation-cart-totals`, base `1e7b2c66cf0491ddc6723c2672e7a29fe9d76769`, worktree `.local-worktrees/spring-public-runtime`. El árbol inicial contenía 87 eliminaciones y 11 modificaciones de la depuración anterior, con informes y dos pruebas nuevas sin seguimiento. El índice estaba limpio. El checkout principal del backend estaba en `44df746`. La web activa estaba en `fix/brevo-email-enrollment` (`0b870ae`) con ediciones de otros colaboradores y el worktree Vercel anterior en `dd62bbe`. El móvil estaba en `feature/actividad-4-integracion-movil` (`d765429`). Se mantuvieron sus cambios e historiales.

Se revisaron completos los dos informes previos. El JAR de referencia se conservó con SHA-256 `70dfabbd053aee01fcd814e416d7a484ba3f0f8e0fb1c7230d23fe5ca71a8943` y el candidato previo con SHA-256 `1a210063332fadd9c66f32ee7a83ab6d0b1a45f22f75d754dac0628da27dc755`. Maven se ejecutó en una copia de compilación independiente bajo `target/final-audit-build`. El árbol de dependencias, inventario por archivo, rutas y anotaciones Spring se conservan en `target/render-free-lab/final-runtime-dependencies.txt` y `final-audit-inventory.txt`.

## Inventario y clasificación

A indispensable, B compartido, C opcional con posible inicialización condicional, D retirado o candidato con evidencia, E requiere prueba adicional.

| Clasificación | Componentes | Dependencia o motivo |
| --- | --- | --- |
| A | Registro, verificación, login, MFA, JWT, sesiones, roles, contraseñas y privacidad | `SecurityConfig`, filtro JWT, interceptores, Bean Validation, seguridad de métodos, token y código de un solo uso, audit logs. Spring Security 7.0.8 y JJWT. Clientes web y móvil comparten estos contratos. |
| A | Catálogo, marcas, categorías, búsqueda, carrito, favoritos, cotización, pedidos y checkout | Controladores públicos y privados, DTO de contrato, repositorios Hibernate 7.2.25.Final y Flyway V56. El catálogo usa joins explícitos para categoría/marca y consulta de conteo. |
| A | Administración comercial, archivos, integraciones operativas y salud | CSV, validación de subida, Cloudinary, API de pagos, alertas de inventario y Actuator. Los perfiles mantienen sus controles. |
| B | Caffeine, Commons CSV, Jackson 2, HttpClient y almacenamiento | Caffeine es usado directamente por el límite de verificaciones. CSV es comercial. Jackson 2 tiene consumidores directos de DTO y proveedores. HttpClient está vinculado al transporte e integraciones. |
| B | Correo saliente y recuperación | EmailService, Brevo, Resend y SMTP. Se conservaron las configuraciones Mailgun por existir puntos de inyección. El uso efectivo de Mailgun requiere confirmar el contrato del proveedor por separado. |
| B | Planificador y ejecución asíncrona | Respaldo, sesiones, tokens, cuentas, auditoría, stock, expiración de pedidos y reconciliación. Pool del planificador de cinco hilos y ejecutor separado para respaldo (1 base, 2 máximos, cola de 5). |
| C | Adaptadores de entorno y paneles comerciales poco frecuentes | Mantener hasta medir uso real y latencia de primera petición. Storage local sirve a laboratorio y Cloudinary al entorno de aplicación. |
| D | Modelos K-Means/SMA, métricas administrativas y pruebas auxiliares | Los módulos retirados en el incremento anterior ya carecen de consumidor ejecutable en el árbol candidato. Se conservan migraciones y datos históricos. |
| D | OAuth2 experimental y SendGrid | La clase OAuth2 tenía un hilo periódico y carecía de controlador, consumidor o ruta en ambos clientes. El SDK SendGrid era una dependencia de runtime sin llamadas. Se retiraron junto con sus propiedades exclusivas. |
| D | `ValidationTester`, `SimpleValidationTest` y `test-orders-data.sql` | Utilidades de prueba bajo `src/main` sin referencias ni inicialización. Se retiraron del artefacto. `TestController` y `ValidationTestController` conservan su perfil local/dev y el acceso restringido, por servir al diagnóstico manual. |
| E | SQL de pedidos paginados, asesoría de índices, perfiles y memoria | `OrderService.getOrdersByUserId` carga una página y consulta autorizados dentro de su mapeo por pedido. El patrón es un N+1 confirmado por código. El coste y el efecto sobre el p95 requieren pedidos poblados y medición SQL. Evitar joins de múltiples colecciones en la misma consulta paginada sin probar cardinalidad y conteos. Las pruebas de alta concurrencia con datos realistas siguen siendo necesarias para atribuir picos de GC, fugas o saturación de Hikari. |
| E | Permisos del rol PostgreSQL, bucket externo y ciclo de cron | Revisar privilegios de DDL y dump del rol de staging, recuperación de objetos y permisos, autenticación real de Supabase, alertas en fallo del proceso asíncrono y exclusión entre despliegues. El motor dinámico registra la solicitud de respaldo como iniciada antes de que el worker determine su resultado. La bitácora del respaldo es la fuente del estado final. |

El inventario fuente previo a esta última depuración registró 45 controladores, 78 archivos de servicio, 49 archivos en paquete de repositorios, 45 entidades, 88 DTO, 17 configuraciones, ocho archivos de seguridad y un interceptor. Hibernate detectó 47 interfaces JPA en el candidato anterior. Los mapeos de API existen en servicios y controladores. El árbol runtime reconstruido confirmó Spring Boot 4.0.9, Spring Framework 7.0.10, Tomcat 11.0.27, Hibernate 7.2.25.Final y HikariCP 7.0.2. El retiro de SendGrid también quitó de ese árbol su cliente y el proveedor criptográfico que entraban por esa ruta. Caffeine y Commons CSV permanecen.

## Regresión y diferencias de cobertura

La diferencia histórica se explica por **245 − 95 + 2 = 152**. Casos retirados en Surefire, incluidas clases anidadas:

| Casos retirados | Cantidad |
| --- | ---: |
| Controladores de pronóstico | 5 |
| Controlador K-Means y sus casos anidados | 10 |
| Cálculo, prioridades y servicio de demanda | 19 |
| Analítica K-Means y predicción K-Means | 25 |
| Evaluador de índices del monitoreo retirado | 28 |
| Tendencias públicas y sus casos en controlador de recomendaciones | 8 |
| **Total** | **95** |

Se agregaron dos casos de almacenamiento y posteriormente uno de acceso a respaldos. El flujo GitHub prepara los tres esquemas de prueba y una base independiente de catálogo. `CMC_CATALOG_TEST_PASSWORD` habilita la suite PostgreSQL de catálogo. En local se replicó con PostgreSQL desechable y una carpeta de build nueva. El ensayo histórico de migración usa una base vacía por corrida.

## Seguridad y estabilización

- Verificar en el digest candidato el runtime Java 21 no-root, configuración de subida y los clientes PostgreSQL. Mantener los límites base de JVM/Tomcat/Hikari hasta que haya datos comparables bajo uso comercial.
- La inspección de entradas encontró tope de 6 MB para multipart, validadores de imagen/CSV, autorización por endpoint y DTO, y separación de secretos por entorno. La auditoría local no sustituye la validación de permisos externos de las cuentas Neon y Supabase.
- Cotejar OWASP y Sonar en una ejecución autorizada del mismo SHA antes de promover. La cobertura de Java se genera con JaCoCo y el workflow conserva el chequeo de acceso Sonar y el Quality Gate. Sin publicación del candidato local, el gate histórico corresponde a otro SHA.
- OWASP Dependency-Check 13.0.0 encontró `CVE-2020-13956` (CVSS 5.3) en HttpClient 4.5.12, transitivo de `googleauth`. Se fijó 4.5.14 en `dependencyManagement`. El árbol Maven confirmó la sustitución y el escaneo posterior con la copia NVD del 11 de noviembre de 2026 terminó bien. El analizador Sonatype OSS Index reportó falta de credencial, por lo que esa fuente no cubrió el escaneo. El modo Maven offline fue rechazado por el plugin y se repitió en modo normal con la copia NVD. El umbral CVSS 7 está intacto. [Aviso de Apache](https://hc.apache.org/news.html) · [Ficha NVD](https://nvd.nist.gov/vuln/detail/cve-2020-13956)
- Pasaron los nueve casos locales del script de diagnóstico Sonar. Para investigar un bloqueo del servicio, distinguir la validación de credencial, la pertenencia al proyecto y la importación de JaCoCo/LCOV antes de interpretar el Quality Gate. Esta corrida local no acreditó el servicio remoto.
- Las rutas de salud y las tareas de seguridad siguen activas. El arranque de staging debe medir cierre ordenado, trabajo programado y memoria antes de cambiar el tamaño de pool o reducir heap. El proceso HTTP y un respaldo programado comparten CPU mientras el cron permanezca dentro de la API.
- La preparación de capacidad, presupuesto y rollback se documenta en el [informe de Railway](../deployment/RAILWAY_READINESS_ASSESSMENT.md).

## Resultados de la ejecución local

Evidencia histórica reproducida en los informes previos a 512 MiB y 1 CPU. El retiro académico bajó el arranque de 71.1 s a 50.7 s en la corrida comparable. El pico total subió de 457.7 a 458.4 MiB y la CPU de 6.48 a 7.68 CPU-s. Esos datos no prueban ahorro sostenido de RAM o CPU y no predicen la capacidad de Railway.

La evidencia nueva se conserva en `target/render-free-lab`. Los ensayos de Docker usan red privada, PostgreSQL local, Mailpit y un emulador HTTP de almacenamiento con información sintética. No equivalen a entrega de correo Brevo, descarga real de Supabase, compra real ni aceptación física de Android.

- La regresión anterior al parche y la posterior a HttpClient 4.5.14 aprobaron 153 Maven sin omitidas sobre PostgreSQL 18 desechable. Angular compiló y aprobó 117 casos en Chrome. Ionic aprobó 168 casos de integración. Las pruebas de checkout y permisos usan sus contratos y credenciales sintéticas. El repositorio web y el móvil conservan el origen Render. Su cambio es un paso explícito de staging, pues las compilaciones de cliente actuales no ejercen Railway.
- La imagen Docker comprobada ejecuta Temurin 21.0.13 como UID 1001 y contiene `pg_dump` y `pg_restore` 18.7. Con la imagen se comprobó `verify-full` y TLS 1.3 contra un servidor con CA y SAN sintéticos. Un respaldo completo y otro limitado a `catalog.products` se restauraron en bases de destino distintas. Ambos conservaron las dos filas esperadas. Los hashes están en `final-tls-backup.log`.
- El primer arranque Docker contra la base sintética clonada falló porque Flyway encontraba la baseline de test registrada y el JAR de producción no la trae. Se corrigió el montaje de solo lectura para el banco de pruebas, conservando la validación Flyway activa. Esa baseline es exclusiva del laboratorio.
- El ensayo posterior reveló que el perfil `LOCAL` necesitaba escribir en `/app/uploads` y el UID de la imagen carecía de permiso. El Dockerfile crea ese directorio y asigna su propiedad a `appuser:appgroup`. El proceso continúa ejecutándose como UID 1001 y el código del JAR no requiere permisos ampliados.
- El cron acelerado encontró una excepción reproducible al purgar tokens de verificación vencidos sin transacción. Se aplicó `@Transactional` exclusivamente a `VerificationTokenRepository.deleteExpiredTokens`. La suite focalizada aprobó 9/9 sobre PostgreSQL y comprobó que se elimina el token vencido y permanece el vigente.
- El cliente PostgreSQL también rechazó un nombre de servidor distinto del SAN con `verify-full`. Los resultados están en `final-tls-negative.log`.
- Los logs de la imagen mostraron actividad DEBUG de seguridad heredada de la configuración base. Se observó volumen por solicitud. La duración aislada de esa instrumentación queda pendiente, al igual que validar la política de logs y retención del proveedor. No se identificó un secreto en las muestras saneadas evaluadas.

## Cierre del candidato Docker

Se ensayaron dos arranques independientes por variante con 1 vCPU y 2 GiB. Ambas variantes usaron la misma imagen de diagnóstico y sus capas de Java, con el JAR previo montado solo para las referencias. La carga comparable ejecutó 172 solicitudes por variante durante 76–77 segundos, con seis solicitudes concurrentes. La segunda corrida de cada variante aporta las muestras de memoria, CPU y latencia de la tabla. Datos completos en `target/render-free-lab/final-image-v4-{before,after}-{1,2}.{json,log}`. Imagen candidata `sha256:98ca59c9a7e4cc1a0a1c737320ff48224c8cf52389f5df2be39c999623161b75`.

| Medición | Referencia | Candidato |
| --- | ---: | ---: |
| Arranques a UP | 45.19 y 38.12 s | 35.15 y 34.39 s |
| Pico total del cgroup | 463.93 MiB | 461.07 MiB |
| Pico RSS de Java | 475.11 MiB | 471.07 MiB |
| Heap comprometido | 224 MiB | 224 MiB |
| Heap usado tras el último GC observado | ~90 MiB | ~92 MiB |
| Metaspace usado / comprometido al último GC | 115.4 / 116.9 MiB | 113.9 / 115.4 MiB |
| NMT comprometido total / sin heap | 434.9 / 210.9 MiB | 433.6 / 209.6 MiB |
| Hilos | 42 | 41 |
| Pausas GC registradas, de arranque a apagado | 124 | 125 |
| CPU consumida durante la carga | 11.15 CPU-s | 13.36 CPU-s |
| Períodos acumulados con throttling / total | 164 / 702 | 388 / 684 |
| Hikari máximos total / ociosas / activas / pendientes | 4 / 4 / 4 / 2 | 4 / 4 / 4 / 2 |
| HTTP completadas / errores | 172 / 0 | 172 / 0 |
| OOM y reinicios | 0 | 0 |

RSS, cgroup y NMT tienen alcances y momentos de muestreo distintos. Los máximos de Hikari se observaron en instantes distintos. El arranque fue más corto en ambas corridas del candidato. Las diferencias de RAM son pequeñas y la CPU y el p95 subieron en varias rutas con mayor contención. La muestra no permite atribuir esa diferencia al código ni certificar tráfico de hora pico o estabilidad de 24 horas.

| Endpoint | Referencia p50 / p95 (ms) | Candidato p50 / p95 (ms) |
| --- | ---: | ---: |
| Login | 685 / 803 | 368 / 1489 |
| Catálogo | 15 / 26 | 15 / 79 |
| Búsqueda | 21 / 73 | 23 / 193 |
| Detalle | 11 / 19 | 11 / 16 |
| Carrito, lectura | 96 / 107 | 99 / 304 |
| Pedidos, lectura | 90 / 231 | 91 / 296 |
| Sesión JWT | 91 / 103 | 93 / 294 |

La comprobación independiente de la imagen confirmó escritura en /app/uploads con UID 1001 y clientes PostgreSQL 18.7. El candidato ejecutó los trabajos acelerados de respaldo y limpieza; registró dos respaldos completos dentro de seis solicitudes y completó los cuatro pasos de mantenimiento. La referencia reprodujo la excepción de transacción y completó tres pasos. Una copia completa recuperada del storage de laboratorio se restauró en PostgreSQL independiente con Flyway V56, 55 entradas, cero fallos y ocho productos. El SHA-256 de ese respaldo sintético fue `bdac85d1d5be7032d33c9d678d083291cc2b4fdb1ee36fa342b3d92fd67b642e`. El ensayo previo cubrió además restauración parcial y rechazo de un hostname TLS incorrecto. Estas pruebas conservan el cron en la API.

La compilación de publicación se ejecutó en `target/audit-publication-build` contra PostgreSQL aislado y aprobó **153/153**, sin omisiones. Los contratos ejercitados incluyen autorización de respaldo, registro y recuperación, MFA, JWT, catálogo y carrito. Las pruebas HTTP y los ensayos de cron complementan la suite Maven. Siguen pendientes la entrega real de Brevo, el bucket real de Supabase, la carga con una distribución representativa y la aceptación física sobre el nuevo origen. El móvil no requiere cambio para mantener sus rutas actuales. Las migraciones productivas permanecen intactas.

**Capacidad de partida para staging.** Reservar 1 vCPU y 2 GiB para una réplica, observar métricas, costo, p95 y un ciclo real de trabajos antes de cambiar el origen de los clientes. El laboratorio anterior comprobó arranque a 512 MiB con 1 CPU y falta de UP tras diez minutos con 0.1 CPU; la candidata final se ensayó con 2 GiB. No se propone reducir heap ni retirar controles para operar en Render Free. Los costos y el procedimiento de reversión figuran en el informe de Railway.

La revisión Angular se preparó desde la base remota `dd62bbe` en un worktree aparte para preservar las ediciones locales de otros flujos. El build y los 105 casos de esa revisión aprobaron; los 117 casos de la corrida anterior correspondían al árbol de trabajo original. Se retiraron también las entradas PWA de las rutas de tendencias. El selector de tablas exige el nuevo endpoint administrativo del backend: validar el par en staging y coordinar ese cambio antes de promover. Se revalidó el JAR empaquetado y su classpath contiene HttpClient 4.5.14 y HttpClient5 5.6.4. OWASP procesó 91 dependencias, sin hallazgos en las fuentes disponibles.
