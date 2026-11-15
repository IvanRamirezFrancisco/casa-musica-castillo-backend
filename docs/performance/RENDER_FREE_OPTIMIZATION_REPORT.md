# Optimización del backend para Render Free

**Decisión 3 · Render Free no es viable para el servicio comercial con los recursos probados.** El candidato evitó el OOM con 512 MiB y 1 CPU durante la carga sintética. Con 0.1 CPU tardó 16 min 32 s en arrancar y el p95 de login fue 13.49 s en la prueba posterior. Render suspende los servicios Free tras 15 minutos de inactividad. [Planes de cómputo](https://render.com/docs/compute-plans) · [Comportamiento de Render Free](https://render.com/docs/free)

## Código y alcance

- Worktree `.local-worktrees/spring-public-runtime`, rama `feature/email-activation-cart-totals`, base `1e7b2c66cf0491ddc6723c2672e7a29fe9d76769`. Los ajustes recuperados estaban sin preparar para commit. Se conservaron los dos cambios de runtime y se añadió este informe.
- JAR de Maven `target/auth-system-1.0.0.jar`, SHA-256 `70DFABBD053AEE01FCD814E416D7A484BA3F0F8E0FB1C7230D23FE5CA71A8943`.
- JAR original del laboratorio `baseline.jar`, SHA-256 `C5B656C63A83E39AE706A899D96E8F629650C9495CCBE2371D4124CBCA87DE2E`. `candidate.jar`, SHA-256 `FB4CB31C40F128320BA3359988F7F35DA0DDC92B8D817FD40A663BFCA6597BAD`. El JAR de laboratorio difiere del original en el perfil de producción y coincide con el JAR de Maven en los 813 archivos de clases, recursos de aplicación y dependencias. La prueba focalizada usa directamente el JAR de Maven.
- Las pruebas utilizaron PostgreSQL 18.6 desechable, datos sintéticos, Mailpit, almacenamiento local y pagos desactivados en una red Docker interna. V54 `-859948869`, V55 `2107538289` y V56 `-2138945592` figuran exitosas en el historial local. El trabajo quedó en el árbol local sin commit, push, merge ni despliegue.

## Configuración recuperada y validada

| Archivo | Antes | Candidato | Efecto observado o riesgo |
| --- | --- | --- | --- |
| `Dockerfile` | Heap inicial 256 MiB y máximo 384 MiB | 64 MiB iniciales, 224 MiB máximos | El heap acabó comprometido al máximo. Reducirlo más requiere medir pausas y presión del heap. |
| `Dockerfile` | G1, JIT por defecto, tamaño de pila y caché por defecto | Serial GC, `TieredStopAtLevel=1`, caché de código de 48 MiB, `-Xss512k` | El paquete de cambios evitó el OOM en el perfil de 1 CPU. Se ensayó en conjunto. Serial GC y JIT reducido pueden limitar throughput con más carga. |
| `Dockerfile` | Shell como PID 1 | `exec java` | Java recibe la señal de parada del contenedor. |
| `application-production.yml` | Tomcat por defecto, 10 hilos de reserva y 200 de máximo | 2 y 24, configurables por entorno | Se observaron 30–32 hilos de proceso. El ensayo de tres solicitudes concurrentes deja sin medir la saturación del servidor HTTP. |
| `application-production.yml` | Hikari, 5 conexiones ociosas mínimas y 10 máximas | 1 y 4, configurables por entorno. Mismos timeouts. | Hikari abrió hasta 4 conexiones. En muestras periódicas se vio 1 activa en el arranque y 4 ociosas al finalizar la actividad inicial. `waiting=0` en las muestras disponibles. El intervalo de muestreo no permite descartar esperas breves. |

Se conservaron los ejecutores, el planificador y el arranque de las comprobaciones críticas. El ejecutor de respaldos usa 1 hilo base, 2 máximos y una cola de 5. El planificador de automatizaciones usa 5 hilos y registró tres trabajos activos. Los modelos analíticos empaquetados suman aproximadamente 53.7 kB. No hay una medición aislada que justifique diferirlos o reducir sus ejecutores. Tampoco hay cambios en Java, migraciones, dependencias, autenticación ni contratos REST.

## Ensayos recuperados

| Perfil | Arranque | Pico cgroup observado | RSS máximo observado | Carga y resultado |
| --- | ---: | ---: | ---: | --- |
| Original · 1 CPU · 512 MiB | OOM a 124.4 s | 497.8 MiB en la última muestra anterior al OOM | 515.7 MiB | Salida 137, `OOMKilled=true`, sin llegar a UP |
| Original · 0.1 CPU · 512 MiB | 650 s sin UP en el sondeo. OOM posterior a ~19 min | 503.8 MiB en la última muestra anterior al OOM | 517.6 MiB | `OOMKilled=true`, sin tráfico funcional |
| Optimizado · 1 CPU · 512 MiB | UP a 97.3 s | 441.8 MiB al llegar a UP. Véase medición sostenida instrumentada más abajo | 453.2 MiB al llegar a UP | 122 solicitudes correctas, 0 errores, 310 s de carga ligera, sin OOM ni reinicios |
| Optimizado · 1 CPU · 2 GiB | UP a 167.7 s | 469.8 MiB | Hasta 474.0 MiB en el seguimiento que incluye el cambio posterior de cuota | 122 correctas, 0 errores, 310 s de carga ligera |
| Optimizado · 0.1 CPU · 512 MiB, frío | El sondeo expiró a 643.4 s. Spring terminó a 991.9 s desde el inicio del contenedor | 456.1 MiB hasta la última muestra archivada de ese perfil | 470.0 MiB en la misma ventana | 122 correctas, 0 errores, 2145 s de carga posterior. Sin OOM ni reinicios observados |
| Optimizado · 0.1 CPU · 512 MiB, caliente | Arrancó a 1 CPU y 2 GiB. Se redujeron ambos límites en vivo | El cgroup conserva el máximo histórico de la instancia | El proceso conserva su máximo histórico | 122 correctas, 0 errores, 348 s |
| Optimizado · 0.1 CPU · 2 GiB, frío | Sin registro reproducible de una corrida independiente | — | — | No se utilizó para el dictamen |

Los registros iniciales compartieron CPU con Maven y otros contenedores. La duración de 167.7 s frente a 97.3 s no mide un efecto causal de la RAM. RSS (`VmHWM`) y cgroup usan contabilidades distintas. Los picos anteriores a un OOM son cotas inferiores del pico real.

| Operación | 1 CPU / 512 MiB p50 / p95 | 0.1 CPU / 512 MiB p50 / p95 |
| --- | ---: | ---: |
| Login sin MFA, 4 muestras por perfil | 694 / 3076 ms | 6400 / 13491 ms |
| Catálogo, 31 muestras | 32 / 103 ms | 398 / 799 ms |
| Búsqueda, 30 muestras | 41 / 412 ms | 598 / 895 ms |
| Sesión JWT, 31 muestras | 110 / 274 ms | 1100 / 1699 ms |
| Pedidos, 7 muestras | 151 / 707 ms | 899 / 1203 ms |

El envío/verificación de MFA Email tardó 1072/758 ms a 1 CPU y 6508/5861 ms a 0.1 CPU, con una muestra por operación y perfil. El p95 de cuatro logins corresponde al mayor observado. Los tiempos del cliente corresponden al laboratorio local y excluyen latencia real de Neon o Brevo.

## Medición sostenida del JAR de Maven · 512 MiB y 1 CPU

El JAR de Maven alcanzó UP en **33.8 s** en una corrida complementaria con menor contención del host. Después de la prueba focalizada, completó **122 solicitudes, 0 errores, 303 s de carga repetida**, sin OOM ni reinicios. Docker confirmó la parada posterior mediante SIGTERM con salida 143. La fase repetida usa tres solicitudes concurrentes en cada ronda y pausas de 10 s. El conteo de 122 incluye la preparación funcional. Las 17 muestras de cgroups cubren unos 323 s.

| Indicador | Resultado |
| --- | --- |
| Cgroup, pico histórico de la instancia | **479.8 MiB**, con margen de **32.2 MiB** sobre el límite |
| Cgroup, muestras durante la carga | 460.7–462.6 MiB. `memory.failcnt=0` |
| RSS máximo de Java | 472.8 MiB |
| Heap Java | 152.3 MiB utilizados al llegar a UP en el primer ensayo y 120.9 MiB al final del ensayo complementario. 224 MiB comprometidos en NMT en ambas lecturas |
| Metaspace con espacio de clases, al final | 114.1 MiB utilizados, 116.4 MiB comprometidos |
| Memoria comprometida según NMT, al final | 434.5 MiB total. 210.5 MiB al descontar el heap. Incluye metaspace, código, hilos y otras categorías de HotSpot |
| Hilos | 31–32 hilos del proceso. categoría Thread de NMT al final con 3.0 MiB comprometidos y 16.7 MiB reservados |
| CPU durante el muestreo | 4.36 CPU-s en 323 s de pared (~1.35% de 1 CPU de promedio en esta carga ligera). throttling en 4/803 períodos de cgroup activos |
| Hikari | Hasta 4 conexiones totales. Muestra inicial 1 activa, 0 ociosas. durante la carga se muestrearon 3–4 ociosas, 0 activas y 0 pendientes entre ráfagas |
| Login p50 / p95 | 345 / 358 ms, 4 muestras |
| Catálogo y búsqueda p50 / p95 | 12 / 21 ms y 17 / 43 ms |
| JWT y pedidos p50 / p95 | 36 / 67 ms y 34 / 71 ms |
| MFA Email envío / verificación | 376 / 327 ms, 1 muestra de cada operación |

El pico de 479.8 MiB se estableció antes de la primera muestra de carga. No se lo atribuye al tráfico sostenido. El NMT comprometido mide categorías de HotSpot y el cgroup mide el contenedor. La carga no representa concurrencia comercial ni el solapamiento con respaldos, trabajos nocturnos o picos de tráfico. El OOM original quedó resuelto para las ventanas probadas, con margen insuficiente para asegurar estabilidad general.

Evidencia en `measured-soak.log`, `measured-soak-samples.txt`, `measured-soak-jvm-end.txt` y `hikari-samples.txt`. La imagen de diagnóstico usa JDK amd64 y NMT. Aún se debe verificar el contenedor JRE no-root construido desde el Dockerfile en el entorno elegido.


## Causa del arranque lento

A 0.1 CPU, el candidato alcanzó 6330/6352 períodos limitados por cuota en el primer sondeo y 11086/11151 en el seguimiento. El original alcanzó 6411/6415 en su primer sondeo. El tramo largo se distribuye entre la creación del contexto, repositorios y JPA, y la inicialización posterior de Spring. El arranque de JPA comenzó 183 s después del inicio del contenedor. El escaneo de 52 repositorios tomó 18.3 s y el EntityManagerFactory quedó listo a los 561 s. La validación de Flyway V56 tomó 4.3 s, con el esquema ya actualizado. Los logs muestran el registro de automatizaciones y la carga del modelo de clustering cerca del final, sin aislar un tiempo exclusivo por componente. La cuota explica la limitación dominante observada. No hay evidencia para atribuir el arranque completo a Flyway, Hikari, autenticación o un modelo.

## Regresión y evidencia

- Maven recuperado de `regression.log`, **245/245**, 0 errores, fallos u omitidos, `BUILD SUCCESS`. Se verificó el hash del JAR resultante. No se repitió la suite porque la configuración probada quedó intacta.
- La aceptación HTTP comprobó registro, activación, login, MFA Email vía Mailpit, TOTP, JWT, catálogo, búsqueda, historial y alta, edición y eliminación de carrito. Las pruebas verificaron importes de MX$8,700 y MX$17,400.
- Comprobación adicional con el JAR de Maven, **18/18**. Incluye detalle de catálogo, cotización de MX$8,700, recuperación mediante Mailpit, uso único, invalidación de la sesión anterior y login con la contraseña nueva. Sesión sin token, JWT inválido, carrito anónimo y pedidos anónimos devolvieron 401. Se verificó el historial de pedidos. La creación de pedidos y los pagos quedaron fuera de la carga sostenida.
- Evidencia local en `target/render-free-lab/`. Los archivos `lab.py` y `traffic.mjs` documentan los perfiles y la secuencia HTTP. `focused-lab.py`, `focused.mjs`, `focused-metrics.txt`, `focused-traffic.log` y `free-cold-phases.txt` contienen la comprobación complementaria. Los JSON conservan el sondeo de arranque y NMT. `sustained-metrics.txt` conserva muestras originales de tres perfiles. Los archivos `*-traffic.log` contienen los percentiles por operación. Al recuperar la ejecución, los contenedores en curso figuraban con salida 255 y `OOMKilled=false`; esa salida queda fuera de las ventanas de carga completadas. Un error de codificación de Python en Windows al exportar el log dejó archivos de arranque vacíos. Los JSON y los logs de Docker permitieron recuperar los hitos y el script complementario escribe en UTF-8.
- Reproducción local con Docker Linux y las imágenes `postgres:18-alpine`, `axllent/mailpit`, `node:22-alpine` y `eclipse-temurin:21-jdk-alpine` en caché. Conservar los artefactos con sus hashes. `python target/render-free-lab/lab.py setup` inicializa una red interna y la base temporal cuando el laboratorio aún no existe. Arrancar `candidate-1cpu-512` o `candidate-2g` con el mismo script y cargar `fixture-product.sql` exclusivamente en ese PostgreSQL temporal. El fixture `LAB-V56-01` cuesta MX$7,500. Ejecutar `traffic.mjs` dentro de la red interna con el nombre del contenedor como argumento, sin puertos publicados. Mantener `--memory-swap` igual a `--memory`. Las métricas usan Temurin JDK para disponer de `jcmd`; para staging queda confirmar el resultado con la imagen JRE no-root construida desde el Dockerfile.

## Alojamiento

| Opción | Precio y capacidad | Decisión |
| --- | --- | --- |
| Render Free | USD 0, 512 MB publicados y 0.1 CPU | No apto para este backend comercial. El arranque en frío y el login bajo cuota comprometen la disponibilidad. [Cómputo](https://render.com/docs/compute-plans) |
| Oracle Always Free Ampere A1 | USD 0 dentro de 1,500 OCPU-h y 9,000 GB-h mensuales en la región principal, una asignación permanente separada del crédito promocional | Primera opción para staging si existe capacidad. Probar 1 OCPU y 4 GB en Querétaro (`mx-queretaro-1`), dentro de la cuota publicada. Comprobar la cuota disponible en **Governance & Administration → Limits, Quotas and Usage → Compute** y que aparezca `VM.Standard.A1.Flex` como elegible. La capacidad efectiva de la cuenta no se verificó. Oracle puede recuperar instancias ociosas. La imagen oficial Temurin 21 JRE Alpine publica ARM64. Queda pendiente validar el build completo y las dependencias en A1. [Cuota y recuperación de instancias](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) · [Región](https://docs.oracle.com/en-us/iaas/releasenotes/changes/75aca8c4-b80f-4892-bc5c-b6748ec4b5d7/) · [Imágenes ARM64](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin) |
| Railway Hobby | USD 5/mes mínimo que incluye USD 5 de uso. USD 10/GB-mes de RAM, USD 20/vCPU-mes de CPU y USD 0.05/GB de salida | Configurar capacidad de ráfaga de al menos 1 vCPU y límite de 2 GiB para el ensayo. El plan admite este perfil dentro de sus máximos publicados por servicio, que incluyen hasta seis réplicas. Ejemplo USD 7.05/mes con 0.50 GB y 0.10 vCPU de consumo promedio y 1 GB de salida. Es un escenario, no una factura proyectada. Simplifica operación, con costo variable y menor control del host. [Tarifas y límites](https://docs.railway.com/pricing/plans) |
| DigitalOcean Basic Linux | USD 12/mes, 1 vCPU compartida, 2 GiB de RAM, 50 GiB SSD. Respaldos opcionales con costo adicional | Alternativa con costo previsible. Requiere gestionar parches, TLS, firewall, reinicios y monitoreo. [Precio oficial](https://www.digitalocean.com/pricing/droplets) |

En las tres alternativas se conserva Neon, Vercel, Brevo y Cloudinary. El mínimo ensayado de 1 CPU no equivale a una garantía de rendimiento entre proveedores. Para disponibilidad continua, verificar capacidad y política de recuperación de Oracle antes de usarlo como origen comercial.

## Preparación de staging

1. Verificar capacidad real y arquitectura. Construir desde el worktree revisado, fijar el digest de la imagen y validar el Dockerfile de runtime en esa arquitectura. Comprobar parada controlada y recuperación tras un reinicio.
2. Aislar la red, el esquema V56, las cuentas sintéticas y el correo de pruebas. Usar TLS para la API y para PostgreSQL, almacenamiento de pruebas y pagos desactivados.
3. Ejecutar arranques en frío, tráfico más representativo y al menos un ciclo de tareas programadas. Medir RSS, cgroups, pausas GC, throttling, Hikari, p95 y errores. Probar los flujos web y Android contra el mismo origen HTTPS.
4. Mantener preparado el artefacto previo, la revisión exacta, el inventario y procedimiento de recuperación. Solicitar autorización antes de cambiar infraestructura o promover producción.
