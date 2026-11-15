# Railway Jobs y Decisión Serverless — Casa de Música Castillo

> Fecha de verificación: `2026-11-15`  
> Rama candidata backend: `chore/backend-stability-audit` (PR #11)

---

## 1. Estrategia de Automatizaciones y Tareas Programadas

### 1.1 Jobs deshabilitados automáticamente en Railway (sin alterar Neon)
Implementado en `DynamicSchedulerService.java`:

```java
@Value("${app.jobs.backup-database.enabled:${APP_JOBS_BACKUP_DATABASE_ENABLED:true}}") boolean backupDatabaseEnabled
@Value("${app.jobs.inventory-audit.enabled:${APP_JOBS_INVENTORY_AUDIT_ENABLED:true}}") boolean inventoryAuditEnabled
```

| Job | Variable en Railway | Estado Scheduler en Railway | Capacidades conservadas intactas |
|---|---|---|---|
| `BACKUP_DATABASE_JOB` | `APP_JOBS_BACKUP_DATABASE_ENABLED=false` | **Omitido al arranque** (`isLocallyEnabled` retorna `false`, aunque en PostgreSQL `ops.system_automations.is_enabled` sea `true`) | Respaldo manual desde panel admin, permisos RBAC, descarga firmada (Supabase Storage / almacenamiento privado), historial de respaldos, verificación y restauración |
| `INVENTORY_AUDIT_JOB` | `APP_JOBS_INVENTORY_AUDIT_ENABLED=false` | **Omitido al arranque** | Control de existencias, validación de stock en carrito/checkout, consultas de inventario, reservas y liberaciones transaccionales de stock, alertas comerciales e integridad de pedidos |

> Por compatibilidad con el historial existente, `DB_MAINTENANCE_JOB` también se omite en `DynamicSchedulerService.onStartup()`.

### 1.2 Jobs esenciales conservados y blindajes verificados

| Componente / Job | Función | Protección verificada en código y pruebas |
|---|---|---|
| `SESSION_CLEANUP_JOB` | Depura tokens JWT revocados y sesiones inactivas | Se programa y ejecuta normal cuando `APP_JOBS_*` de los 2 anteriores están en `false` (`DynamicSchedulerServiceTest`) |
| `ACCOUNT_CLEANUP_JOB` | Limpia invitaciones, tokens de reset y verificación vencidos | Conservado en `DynamicSchedulerService` sin alteraciones globales en BD |
| `MercadoPagoReconciliationJob` | Concilia pagos pendientes/procesando/autorizados (`> 10 min`, intervalo `900000 ms`) | Anotado con `@ConditionalOnProperty(prefix = "app.mercadopago.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)`. Variable `APP_MERCADOPAGO_RECONCILIATION_ENABLED=false` reservada **solo** para instancia pasiva/duplicada; en producción única debe permanecer en `true`. Idempotente en `PaymentService.applyMercadoPagoStatus` (preserva `paidAt`, sin re-emitir eventos si ya está aprobado: `PaymentReconciliationIdempotencyTest`) |
| `OrderExpirationJob` | Cancela órdenes `PENDING` vencidas (MP > 60 min; transferencia según `bank.transfer.payment-validity-hours: 72`) | Valida `PaymentStatus.PENDING` y `ShippingStatus.PENDING` antes de intentar cancelar; protege comprobantes en `PENDING_REVIEW` y `APPROVED`; usa bloqueo pesimista `OrderRepository.findByIdForUpdate` dentro de `OrderService.cancelOrder` para evitar cancelaciones duplicadas |

---

## 2. Validación Automatizada de Jobs e Idempotencia (`2026-11-15`)

Ejecución real sobre `.local-worktrees/spring-public-runtime`:

1. **Suite focalizada de cambios Codex:**
   - Comando: `mvn test "-Dtest=DynamicSchedulerServiceTest,MercadoPagoReconciliationJobTest,PaymentReconciliationIdempotencyTest,OrderExpirationJobTest,OrderServiceTransactionTest" -B`
   - Resultado: **19 tests ejecutados, 0 fallos, 0 errores, 0 saltados (`BUILD SUCCESS`)**.
   - Cobertura comprobada:
     - `DynamicSchedulerServiceTest` (3): omisión selectiva de `BACKUP_DATABASE_JOB` e `INVENTORY_AUDIT_JOB` conservando `SESSION_CLEANUP_JOB` y `ACCOUNT_CLEANUP_JOB` sin tocar `ops.system_automations`.
     - `MercadoPagoReconciliationJobTest` (2): carga y omisión condicional de bean `@ConditionalOnProperty` (`app.mercadopago.reconciliation.enabled`), consulta filtrada solo a pendientes referenciados.
     - `PaymentReconciliationIdempotencyTest` (1): re-aplicación de pago aprobado conserva `paidAt` sin interacciones duplicadas.
     - `OrderExpirationJobTest` (5) y `OrderServiceTransactionTest` (8): protección de pagos/envíos no pendientes y comprobantes `PENDING_REVIEW`/`APPROVED`, más bloqueo pesimista `findByIdForUpdate`.
2. **Suite completa backend + PostgreSQL V56:**
   - Primera corrida (`task-132`): `166/169` verdes (`PublicProductCatalogPostgresTest` y `Schema50UpgradeTest` fallaron únicamente por invocación inicial de `psql` con múltiples comandos en un solo `-c` dentro de transacción implícita).
   - Re-ejecución inmediata con preparación limpia de BD (`PublicProductCatalogPostgresTest` + `Schema50UpgradeTest`): `3/3` verdes (`BUILD SUCCESS`), totalizando **169/169 pruebas Java verdes** + `check-sonar-access.test.mjs` (**9/9 pruebas verdes**).

---

## 3. Decisión Sobre Railway Serverless: MANTENER DESACTIVADO

Durante la migración productiva y la aceptación inicial Web + Android, **App Sleeping / Serverless en Railway debe permanecer DESACTIVADO**.

### Razones técnicas identificadas
1. **Actividad continua legitima:** `SESSION_CLEANUP_JOB`, `ACCOUNT_CLEANUP_JOB`, `OrderExpirationJob`, `MercadoPagoReconciliationJob` y las validaciones de pool de Hikari interactúan periódicamente con PostgreSQL y generan tráfico saliente detectado por la plataforma.
2. **Pérdida de ejecución de cron:** Mientras un contenedor está suspendido, los `@Scheduled` y el `ThreadPoolTaskScheduler` interno no se disparan, acumulando órdenes expiradas sin liberar stock y retrasando conciliaciones de Mercado Pago.
3. **Cold start y riesgo de HTTP 502:** El arranque en frío de Spring Boot 4.0.9 + migraciones Flyway V56 + warmup Hikari tarda más que una petición interactiva corta, exponiendo a los usuarios de Vercel y Android a errores `502 Bad Gateway` o timeouts en la primera petición.

### Plan futuro (post-estabilización)
- Estabilizar primero la operación en producción web y Android sin Serverless.
- Si posteriormente se evalúa activar Serverless para ahorrar costos, hacerlo primero en entorno controlado y considerando un ejecutor externo independiente para `OrderExpirationJob` / `MercadoPagoReconciliationJob` previa evaluación de costos y autorización expresa.
