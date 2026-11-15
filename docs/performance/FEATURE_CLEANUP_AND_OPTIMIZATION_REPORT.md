# Depuración funcional y optimización estructural

Backend en `feature/email-activation-cart-totals` sobre `1e7b2c6`. Angular en `fix/brevo-email-enrollment` sobre `0b870ae`, con cambios locales preexistentes. La depuración conserva los ajustes de JVM, Tomcat e Hikari del [informe de Render Free](RENDER_FREE_OPTIMIZATION_REPORT.md). El JAR previo quedó preservado como `target/render-free-lab/pre-feature-cleanup.jar`.

## Funciones retiradas y consumidores

| Función | Retiro en backend | Consumidores retirados en Angular |
| --- | --- | --- |
| Patrones de compra K-Means | Controlador de analítica, servicio, repositorios JPA, entidades, proyecciones, DTO y artefacto JSON | Ruta y menú de patrones, pantallas de predicción y enlaces desde la lista y el detalle de pedidos |
| Pronóstico SMA | Controlador, cálculos, repositorio, DTO, estados y JSON. Retiro de `/api/public/products/trending` y `/{id}/trend-status` | Pantalla de pronóstico, sección de tendencias en Inicio, etiqueta del detalle y reglas PWA de esas solicitudes |
| Monitoreo, mantenimiento manual y consultas lentas | Controladores, servicios, DTO, repositorios exclusivos, caché de métricas y mantenimiento programado | Pestañas, servicios HTTP, estado, pipe y notificaciones de esos módulos |
| Automatizaciones administrativas | API de gestión, DTO de gestión, historial HTTP y ejecución de `DB_MAINTENANCE_JOB` | Pestaña, cliente HTTP y enlaces administrativos de automatizaciones |

El planificador y la bitácora interna siguen atendiendo las tareas configuradas de respaldos, limpiezas de sesiones y cuentas, y alertas de inventario. Las filas históricas y la configuración existente se conservan. La programación ignora la fila heredada de mantenimiento aunque esté habilitada. Se conservan autovacuum, salud, seguridad, MFA y sus códigos de recuperación. Caffeine sigue en Maven para limitar solicitudes de verificación. Commons CSV sigue en uso por la importación comercial. La proyección de inventario del panel comercial, distinta de SMA, y las recomendaciones de producto conservan sus contratos. El móvil carece de referencias a las rutas eliminadas en los árboles de origen inspeccionados.

Angular conserva `/admin/backups` con `DATABASE_BACKUP`. Las visitas a `/admin/gestion-db` redirigen a esa ruta. El selector de respaldos parciales pasó de `/api/admin/automations/tables` a `/api/admin/backups/tables`. Las migraciones Flyway y el esquema quedaron íntegros. Los cambios permanecen locales.

## Respaldos

- **Binarios e integridad.** La imagen Temurin JRE examinada carecía de `pg_dump` y `pg_restore`. El Dockerfile instala `postgresql18-client`. En una instancia de esa imagen se instalaron y ejecutaron los binarios 18.7. El servicio ejecuta `pg_restore --list` antes de subir el archivo y transmite a libpq las opciones TLS explícitas del datasource compatibles con libpq. Las URL JDBC inválidas se reportan sin volcar la cadena.
- **Almacenamiento y descarga.** El servicio verifica que el bucket sea privado antes de subir o firmar. Mantiene la transferencia desde archivo, la descarga autorizada con enlace firmado y la bitácora de fallos. Angular permite pedir un enlace nuevo mientras el respaldo esté disponible. La hora de vigencia se cuenta desde la emisión del enlace. El historial sintético estaba vacío al inicio, por lo que las incidencias históricas de producción requieren su evidencia saneada para atribuir causas.
- **Restauración.** PostgreSQL 18.6 generó un archivo custom con 720 entradas. SHA-256 `8e1a718e9432ef44229fabc8dfd3c88d20b630b85d75e4f91bc24964f29cf8a2`. `pg_restore --exit-on-error` lo restauró en `feature_cleanup_restore`. Origen y destino quedaron en V56, 55 entradas de Flyway y 0 fallidas. Coincidieron 2 usuarios, 8 productos y 0 pedidos. `pg_restore --list` rechazó un archivo vacío. El ensayo usó datos sintéticos.
- **Ejecución externa.** Un host separado puede ejecutar el cliente de la misma versión mayor contra el endpoint directo, con TLS verificado, credenciales de respaldo con privilegios mínimos, archivo temporal privado, SHA-256, carga privada y alertas de fallo. La validación debe incluir descarga, restauración independiente, cotejo de tablas y conteos, verificación de permisos y seguimiento de alertas. El respaldo programado vigente permanece activo hasta validar cobertura y recuperación fuera del servidor.

## Verificación

Los ensayos del backend usan el laboratorio privado con PostgreSQL y Mailpit. No se configuraron credenciales productivas ni pasarelas de pago para la carga. La aceptación física de Android requiere un dispositivo conectado. El bucket de Supabase real requiere una prueba aislada autorizada.

- Maven con PostgreSQL desechable aprobó **152 pruebas**, incluida la regresión de Flyway y las dos pruebas del contrato de almacenamiento contra un servicio HTTP local. Una pasada intermedia reutilizó la base de upgrade y falló por no estar vacía. Se reinicializó únicamente el esquema sintético y la ejecución posterior pasó.
- Angular compiló y aprobó **117 pruebas en Chrome** después del ajuste de descargas. El cliente Ionic aprobó **168 pruebas de integración automatizadas en 12 archivos**. ADB funcionó y no listó dispositivos disponibles.
- El arranque del JAR registró respaldo, limpieza de sesiones y limpieza de cuentas. El trabajo retirado `DB_MAINTENANCE_JOB` no se programó. PostgreSQL reportó `autovacuum=on`. El selector de tablas devolvió HTTP 401 a un cliente anónimo en Docker. Una prueba focalizada adicional, con PostgreSQL y MockMvc, verificó 401 anónimo, 403 con `DATABASE_VIEW` y 200 con `DATABASE_BACKUP`, incluyendo `catalog.products` en la respuesta.
- Cada JAR atendió 36 solicitudes sin errores en la comparación. El recorrido cubrió registro, activación, login, MFA por correo con Mailpit, TOTP, JWT, catálogo, búsqueda, alta, actualización y eliminación de carrito e historial de pedidos. La suite Maven también ejercitó los contratos de cotización y propiedad.

## Rendimiento

Comparación bajo 1 CPU, 512 MiB, sin swap, Temurin 21 y las mismas opciones JVM. Cada JAR arrancó en un contenedor nuevo y atendió cuentas sintéticas con cinco rondas de tres solicitudes concurrentes. La ejecución es corta y comparte host Windows. Los percentiles con pocas muestras describen este ensayo.

| Métrica | Antes | Después |
| --- | ---: | ---: |
| Arranque hasta HTTP UP | 71.1 s | 50.7 s |
| JAR | 86,836,980 B | 86,655,370 B |
| Repositorios JPA detectados | 52 | 47 |
| Pico total de cgroup | 457.7 MiB | 458.4 MiB |
| RSS máximo | 472.0 MiB | 470.3 MiB |
| Heap usado al final | 113.2 MiB | 102.3 MiB |
| Heap comprometido según NMT | 224 MiB | 224 MiB |
| Metaspace comprometido | 115.7 MiB | 114.4 MiB |
| Memoria NMT total comprometida | 433.3 MiB | 431.1 MiB |
| Hilos al final | 32 | 32 |
| CPU consumida durante la carga | 6.48 CPU-s | 7.68 CPU-s |
| Períodos limitados por CPU, acumulados | 184/929 | 171/797 |
| p95 login (2 solicitudes) | 831 ms | 1424 ms |
| p95 catálogo (6 solicitudes) | 103 ms | 85 ms |
| p95 búsqueda (5 solicitudes) | 142 ms | 377 ms |
| OOM, reinicios y errores HTTP | 0 | 0 |

Se observaron 20.4 s menos de arranque (28.7 %) y 181,610 B menos en el JAR. El descenso de RSS fue de 1.7 MiB y el pico total del contenedor subió 0.7 MiB. La CPU en la fase de carga aumentó 1.20 CPU-s. El ensayo no demuestra un ahorro sostenido de RAM o CPU. La concurrencia probada no permite concluir que las latencias mejoraron. Una corrida por variante, ejecutada de forma secuencial en un host compartido, tampoco permite atribuir todo el cambio de arranque a la depuración.

Las mediciones detalladas y los logs de pruebas se conservan en `target/render-free-lab/`.
