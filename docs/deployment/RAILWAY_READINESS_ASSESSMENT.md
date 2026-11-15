# Preparación de Casa de Música Castillo para Railway

Evaluación del 14 de noviembre de 2026. Java 21, Spring Boot 4.0.9 y Flyway V56. La configuración propuesta queda en `railway.json`.

| Plan | Precio y consumo incluido | Capacidad publicada | Adecuación |
| --- | --- | --- | --- |
| Free | USD 0, con USD 1 mensual de consumo | 1 réplica, 0.5 GB RAM, 1 vCPU, 1 GB efímero | Margen de memoria estrecho y crédito insuficiente para el escenario continuo bajo. |
| Trial | Crédito gratuito de USD 5 por única vez, hasta 30 días | Hasta 2 réplicas, 1 GB de RAM y 2 vCPU compartidas según la tabla. Limited Trial restringe salida. | Ensayo breve si la cuenta permite conectividad a Neon y proveedores. |
| Hobby | USD 5/mes, incluye los primeros USD 5 de uso | Hasta 6 réplicas, 48 GB y 48 vCPU como máximos agregados del servicio | Validar 1 réplica con límite inicial de 1 vCPU y 2 GiB. |

Los límites de cuenta se cotejan antes de provisionar. La CPU de 0.1 del perfil anterior produjo arranques de más de 16 minutos. El límite de 2 GiB deja margen respecto al pico anterior de ~458 MiB y permite medir ráfagas y trabajos pesados. [Planes](https://docs.railway.com/pricing/plans) · [Trial](https://docs.railway.com/pricing/free-trial)

## Arquitectura y configuración propuesta

- Mantener un servicio Docker Java con una réplica y Serverless desactivado. El Dockerfile pasa el `PORT` de Railway a la JVM. `railway.json` propone readiness `/actuator/health`, 300 s y tres reintentos ante fallo. El readiness del proveedor actúa durante el despliegue. Configurar monitoreo continuo aparte. Railway termina HTTPS en el borde. [Dockerfile](https://docs.railway.com/builds/dockerfiles) · [Config como código](https://docs.railway.com/config-as-code/reference) · [Healthcheck](https://docs.railway.com/deployments/healthchecks) · [Serverless](https://docs.railway.com/deployments/serverless)
- Configurar `RAILWAY_DEPLOYMENT_DRAINING_SECONDS` y alinear el timeout de Spring con la ventana aprobada. Ensayar la terminación con tráfico y respaldos. Una réplica no evita solapamiento entre despliegues. Asegurar un solo ejecutor de cron durante cutover y rollback. [Terminación](https://docs.railway.com/deployments/deployment-teardown)
- Conservar Neon, Vercel, Brevo, Cloudinary y almacenamiento privado. Usar `SPRING_DATASOURCE_URL` JDBC, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD`, con TLS verificado. Probar el endpoint directo de Neon con pgJDBC y libpq. Medir latencia y Hikari antes de adoptar pooling externo para la API. [TLS Neon](https://neon.com/docs/connect/connect-securely)
- Definir `CORS_ORIGINS`, `APP_BASE_URL`, `FRONTEND_PUBLIC_BASE_URL` y `BACKEND_PUBLIC_BASE_URL` según entorno. Configurar JWT, MFA, Brevo, Cloudinary, Supabase y pagos mediante credenciales aisladas de staging. Brevo debe usar HTTPS porque Railway bloquea SMTP saliente en Free, Trial y Hobby. [Salida de red](https://docs.railway.com/networking/outbound-networking)
- Usar disco efímero para archivos temporales y un bucket privado como destino duradero. Validar capacidad temporal, descarga, hash y restauración. Mantener el cron existente hasta que un trabajo externo apruebe las mismas comprobaciones.
- Definir alerta y límite de consumo en el workspace aislado. El límite duro puede apagar todos los servicios del workspace. [Control de costo](https://docs.railway.com/pricing/cost-control)

## Costos mensuales ilustrativos

USD antes de impuestos, sin tarifas de terceros. El tiempo de CPU de las pruebas cortas no permite extrapolar uso representativo del mes.

| Escenario | RAM a 10 USD/GB-mes | CPU a 20 USD/vCPU-mes | Salida a 0.05 USD/GB | Volumen a 0.15 USD/GB-mes | Hobby estimado |
| --- | ---: | ---: | ---: | ---: | ---: |
| Bajo | 0.50 GB = 5.00 | 0.02 vCPU = 0.40 | 1 GB = 0.05 | 0.00 | **5.45** |
| Medio | 0.65 GB = 6.50 | 0.10 vCPU = 2.00 | 10 GB = 0.50 | 0.00 | **9.00** |
| Alto | 1.00 GB = 10.00 | 0.35 vCPU = 7.00 | 50 GB = 2.50 | 5 GB = 0.75 | **20.25** |

Factura estimada = máximo entre USD 5 y el consumo total. Un servicio de staging paralelo agrega consumo. [Tarifas](https://docs.railway.com/pricing/plans) · [Facturación](https://docs.railway.com/pricing/understanding-your-bill)

## Prueba aislada y rollback

1. Preparar Neon desechable V56, HTTPS separado, correo de pruebas y bucket privado. Comprobar TLS, puerto, salud, recursos y digest. Ensayar autenticación, caja comercial sin cargos y restauración.
2. Medir durante al menos un ciclo de operación de staging con tráfico web y Android, cron, reinicio y parada. Cotejar p95, memoria, error y costo con los objetivos aprobados.
3. Ensayar cambio y reversión de origen en web y Android. Verificar host fijo de la APK, CORS, enlaces de activación, callbacks y caché PWA. Un dominio API estable requiere coordinación de DNS y de los clientes existentes.
4. Para una promoción posteriormente autorizada, conservar imagen y configuración de Render, origen previo y respaldo verificado. Coordinar el cambio de tráfico y detener la ejecución duplicada de cron. Ante fallo, devolver tráfico y callbacks a Render, reactivar allí el ejecutor único y verificar salud y datos. La reversión de aplicación conserva el esquema V56. Cambios de esquema futuros requieren un plan de compatibilidad y recuperación propio.

**Decisión técnica.** Hobby es la opción para una prueba aislada de 1 vCPU y 2 GiB. La capacidad efectiva, latencia hacia Neon, entrega Brevo por HTTPS, estabilidad sostenida, aceptación Android y factura deben confirmarse en ese entorno. No se creó ningún recurso Railway.

## Programación selectiva en Railway

En **Variables** del servicio backend, aplicar los valores al publicar un candidato revisado y después de confirmar la cobertura de recuperación indicada abajo:

```dotenv
APP_JOBS_BACKUP_DATABASE_ENABLED=false
APP_JOBS_INVENTORY_AUDIT_ENABLED=false
```

El planificador lee ambos valores al arrancar. Cada variable en `false` impide registrar ese cron aun con una fila `is_enabled=true` en `ops.system_automations`. La ausencia de la variable equivale a `true`. Para volver a habilitar un cron, retirar su variable o fijarla en `true` y arrancar una revisión nueva. La fila debe estar activa según la configuración de base de datos. Ningún ajuste escribe en las filas ni en el historial. El respaldo manual, sus permisos, la descarga firmada, y la consulta de alertas de inventario conservan sus rutas.

| Proceso | Comportamiento |
| --- | --- |
| Sesiones y retos MFA | Cron dinámico y purgas de Spring existentes. El filtro comprueba la firma y expiración JWT y consulta el estado de sesión antes de autenticar. La verificación MFA comprueba la caducidad del desafío al usarlo. |
| Cuentas | Cron dinámico y tareas específicas de recuperación e invitaciones. Los registros abandonados se seleccionan por estado pendiente y antigüedad, sin pedidos ni token vigente y con bloqueo transaccional. |
| Pedidos | Sigue cada 30 minutos por defecto. La cancelación adquiere un bloqueo por pedido, vuelve a comprobar estado de pago y envío y protege comprobantes en revisión o aprobados. |
| Mercado Pago | Sigue cada 15 minutos por defecto. Durante coexistencia, fijar `APP_MERCADOPAGO_RECONCILIATION_ENABLED=false` solo en la instancia duplicada. Mantener un reconciliador activo. El webhook y la reconsulta administrativa conservan sus beans y rutas. |
| Otros trabajos | Se conservan la auditoría de seguridad, los límites de login, las purgas de sesiones y recuperación y el ejecutor manual de respaldos. La inicialización de Hibernate, Flyway y Hikari sigue normal. |

La conciliación consulta pagos existentes. Una aprobación repetida del mismo ID ya aplicado conserva la fecha y evita nuevos eventos de confirmación. Se requiere una prueba en sandbox para confirmar el comportamiento ante webhook y conciliación simultáneos y el resultado de un pago que llega después de expirar una orden. Evitar dos reconciliadores activos. El arranque también revisa respaldos PENDING, por lo que un arranque de la réplica secundaria debe coordinarse con respaldos que estén en ejecución en la principal.

### Recuperación antes del cambio

Se cotejó el SHA-256 del dump productivo del 13 de noviembre de 2026, `c4828777b49f6c100ec1d99b07a61bce843d4a0d7067c1803dacfcc0988c6aa2`. La evidencia local de restauración aislada indica PASS, V53, 51 migraciones y cero fallos. El ensayo se realizó sobre esa revisión y su alcance excluyó un inventario original independiente. Para sustituir el respaldo periódico de Railway, confirmar en Neon la ventana efectiva de recuperación desde la rama raíz, ensayar un punto recuperable en destino separado y verificar que un respaldo actualizado cubra V56 y los datos que deban conservarse. Registrar la antigüedad máxima aceptable del respaldo y el tiempo de recuperación. [Recuperación a un punto en el tiempo en Neon](https://neon.com/docs/postgres/backup-restore/branch-restore)

### Serverless

Mantener Serverless desactivado para este servicio. Railway usa tráfico saliente, incluidos los paquetes hacia PostgreSQL, y puede suspender un contenedor tras unos 5–10 minutos sin salida. Los jobs de sesiones y auditoría tienen intervalos de cinco minutos y pueden producir tráfico incluso sin clientes. Hikari conserva una conexión ociosa por defecto y el pool puede generar tráfico al validar o renovar conexiones. Hibernate y Flyway contribuyen al coste del arranque en frío. Si el servicio llega a suspenderse, los cron internos dejan de ejecutarse hasta que despierte. Esto afecta la puntualidad de la conciliación y la liberación de inventario, y la primera solicitud puede recibir un HTTP 502. Evaluar una réplica aislada solo después de dar a esas tareas una ejecución externa o una garantía de continuidad equivalente. [Funcionamiento de Railway Serverless](https://docs.railway.com/deployments/serverless)

### Ensayo local con Flyway V56

Dos contenedores con el mismo JAR, 1 vCPU, 2 GiB y bases sintéticas clonadas. Las cuatro filas estaban activas y se aceleraron a cron de laboratorio. Cada contenedor se observó durante 90 segundos tras alcanzar UP, sin tráfico de clientes.

| Medición | Ambos jobs habilitados | Ambos jobs deshabilitados |
| --- | ---: | ---: |
| Respaldos completados | 5 | 0 |
| Auditorías automáticas de inventario | 5 | 0 |
| Limpiezas de sesiones y de cuentas | 5 de cada una | 5 de cada una |
| CPU de la API durante la ventana | 1.182 CPU-s | 0.796 CPU-s |
| Pico de memoria del contenedor | 495.57 MiB | 442.91 MiB |
| Pico RSS de Java | 456.69 MiB | 455.89 MiB |
| Hilos al final | 34 | 29 |
| Arranque a UP | 39.25 s | 31.77 s |

No se observaron OOM ni reinicios. El ensayo usó una sola corrida por variante y una frecuencia artificial. No permite extrapolar ahorro mensual ni demostrar mejora sostenida del arranque; las métricas del contenedor excluyen PostgreSQL y el almacenamiento simulados. Evidencias locales en `target/render-free-lab/final-image-jobs1-{enabled,disabled}-2.json`. Maven aprobó 167 pruebas y después aprobó los dos casos adicionales de comprobantes en una regresión focalizada de ocho pruebas.
