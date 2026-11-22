# Migraciones Flyway V60 y V61 — Google Sign-In y Persistencia Segura de Sesiones

**Estado:** Preparadas para revisión y autorización expresa del propietario antes de ejecutarse sobre la base local original `security_db` (`127.0.0.1:5432`, OID `33230`). **No han sido aplicadas sobre `security_db`.**

---

## 1. Compatibilidad e Impacto sobre Datos Existentes

| Migración | Archivo | Cambios DDL | Efecto sobre filas existentes |
|---|---|---|---|
| `V60` | `V60__google_federated_identity.sql` | 1) `ALTER TABLE auth.users ALTER COLUMN password DROP NOT NULL`<br>2) `CHECK (password IS NULL OR length(trim(password)) >= 8)`<br>3) `ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500)`<br>4) `CREATE TABLE auth.external_identities` (`UNIQUE(provider, provider_subject)`, `UNIQUE(user_id, provider)`)<br>5) `CREATE TABLE auth.google_auth_flows`<br>6) `CREATE TABLE auth.consumed_google_tokens` | No modifica nombres, correos, contraseñas ni roles de los usuarios existentes. `avatar_url` queda `NULL` para cuentas existentes. Las tres tablas nuevas inician vacías. |
| `V61` | `V61__harden_refresh_tokens_rotation.sql` | Agrega a `auth.refresh_tokens`: `family_id`, `session_jti`, `absolute_expires_at`, `last_used_at`, `rotated_at`, `revoked`, `revoked_at`, `revoke_reason`, `client_type`, `user_agent`, `ip_address`, índices (`idx_refresh_tokens_user_active`, `idx_refresh_tokens_family`, `idx_refresh_tokens_session_jti`) y reemplaza la FK de `user_id` por `fk_refresh_tokens_user (ON DELETE CASCADE)`. | Si existiera algún token legado sin rotación, lo marca `revoked = TRUE` con `revoke_reason = 'LEGACY_PRE_ROTATION'` para impedir reuso sin hash. No elimina filas. |

No existe `DROP TABLE`, `TRUNCATE` ni `DELETE` en `V60` o `V61`. Ninguna migración `V1`–`V59` fue modificada.

---

## 2. Privilegios Mínimos para `spring_app`

El usuario de aplicación `spring_app` conserva privilegios mínimos (no es superusuario ni propietario):

```sql
REVOKE ALL PRIVILEGES ON TABLE
    auth.external_identities,
    auth.google_auth_flows,
    auth.consumed_google_tokens
FROM spring_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    auth.external_identities,
    auth.google_auth_flows,
    auth.consumed_google_tokens
TO spring_app;

REVOKE ALL PRIVILEGES ON SEQUENCE
    auth.external_identities_id_seq,
    auth.google_auth_flows_id_seq
FROM spring_app;

GRANT USAGE ON SEQUENCE
    auth.external_identities_id_seq,
    auth.google_auth_flows_id_seq
TO spring_app;
```

---

## 3. Procedimiento de Respaldo y Ejecución Autorizada

1. Verificar el plan de solo lectura (sin solicitar contraseña de `postgres` ni modificar datos):

```powershell
cd 'C:\Users\ivanf\Documents\7mo\Proyecto del login original y funcional\segundo proyecto'
powershell -NoProfile -File .\scripts\Invoke-LocalMigrations.ps1
```

(Equivalente mediante `python scripts/prepare-local.py`.)

2. Ejecutar las migraciones **únicamente tras autorización explícita** (genera respaldo nuevo verificado con `pg_dump`, valida la frase exacta `APLICAR security_db V60-V61` y solicita en privado la contraseña de `postgres` mediante `Read-Host -AsSecureString`):

```powershell
powershell -NoProfile -File .\scripts\Invoke-LocalMigrations.ps1 -Apply
```

---

## 4. Diseño Seguro de Respaldo y Recuperación (Contingencia)

**Regla operativa:** No ejecutar `DROP TABLE`, `ALTER TABLE ... DROP COLUMN` manual ni `DELETE FROM public.flyway_schema_history` sobre `security_db`. Esas operaciones destruyen la trazabilidad auditada de Flyway, rompen el historial de migraciones hacia adelante y provocarían pérdida irreversible de datos de cuentas creadas con Google (`password IS NULL`) o sesiones persistentes activas.

### Protecciones integradas
1. **Atomicidad transaccional al migrar (`group = true`):** `scripts/Invoke-LocalMigrations.ps1 -Apply` ejecuta `V60` y `V61` mediante Flyway con `group = true`, `validateOnMigrate = true` y `cleanDisabled = true`. Ante cualquier error SQL o de restricción dentro de `V60`/`V61`, PostgreSQL revierte toda la transacción DDL automáticamente sin alterar `flyway_schema_history` ni datos históricos.
2. **Validación previa de solo lectura (`plan`):** Antes de autorizar `-Apply`, el modo `plan` verifica la identidad exacta (`security_db`, `127.0.0.1:5432`, OID `33230`), checksums intactos de `V1`–`V59`, ausencia de contraseñas no nulas `< 8` caracteres, colisiones de tablas/columnas y llaves foráneas existentes.
3. **Respaldo obligatorio previo con verificación criptográfica:** En modo `-Apply`, `scripts/Invoke-LocalMigrations.ps1` genera automáticamente un respaldo completo en formato custom (`pg_dump -Fc`) en `C:\Users\ivanf\Documents\Respaldos privados Casa Musica\postgresql-before-v60-<timestamp>\security_db-before-v60.dump`, valida la integridad legible del archivo con `pg_restore --list`, calcula su `SHA-256` y exige coincidencia exacta antes de abrir transacción en `security_db`.

### Procedimiento de recuperación ante contingencia grave posterior
1. **Si falló o necesita corrección de esquema tras migrar:** Diseñar una nueva migración incremental **hacia adelante (`V62+`)** manteniendo íntegro el historial `V1`–`V61`.
2. **Si es imprescindible volver exactamente al estado V59:** Con autorización separada y apuntando exclusivamente a un destino vacío designado (sin `clean` ni `DROP` sobre bases con tráfico):
   ```powershell
   & 'C:\Program Files\PostgreSQL\18\bin\pg_restore.exe' --exit-on-error --single-transaction -h 127.0.0.1 -p 5432 -U postgres -W -d <DESTINO_VACIO_AUTORIZADO> "<RUTA_RESPALDO_VERIFICADO>"
   ```
   Verificar la identidad y los conteos de tablas antes de promover ese destino.
