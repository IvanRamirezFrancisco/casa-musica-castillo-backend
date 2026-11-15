# AGENTS.md — Reglas Permanentes del Proyecto (Backend Spring Boot)

Este documento define las instrucciones obligatorias y permanentes para cualquier persona, asistente o herramienta de inteligencia artificial (incluyendo **Codex**, **Antigravity**, **Claude**, **Gemini** o cualquier otro agente) que trabaje en el repositorio **Casa de Música Castillo — Backend (`casa-musica-castillo-backend`)**.

---

## 1. Convención Obligatoria de Ramas

Todas las ramas de trabajo deben describir **qué problema resuelven o qué capacidad entregan**, independientemente de quién las cree o qué herramienta de IA intervenga.

### Prefijos permitidos

- `feature/<funcionalidad>` — Nuevas funcionalidades o capacidades de negocio.
- `fix/<correccion>` — Correcciones de errores funcionales, contratos o regresiones.
- `security/<control>` — Endurecimiento de seguridad, autenticación, MFA, criptografía o remediación de dependencias.
- `test/<pruebas>` — Pruebas unitarias, de integración, regresión o automatización QA.
- `chore/<mantenimiento>` — Tareas de mantenimiento, configuración de build, pipelines o limpieza técnica.
- `docs/<documentacion>` — Documentación técnica, arquitectura, contratos o guías operativas.
- `release/<version>` — Preparación y estabilización de versiones para despliegue.

### Prefijos estrictamente prohibidos

**Queda prohibido crear ramas nuevas con nombres de herramientas, modelos de IA o agentes.**

Ejemplos que **NO** deben repetirse bajo ninguna circunstancia:

- `codex/nueva-funcionalidad`
- `antigravity/correccion-backend`
- `claude/integracion`
- `gemini/desarrollo`

---

## 2. Regla de Continuidad entre Herramientas de IA

Cuando otra herramienta de IA o desarrollador continúe un trabajo iniciado previamente:

1. **Leer el estado Git existente**: ejecutar siempre `git status -sb`, `git branch -avv`, `git worktree list` y `git log --oneline -n 10` antes de realizar cualquier cambio.
2. **Identificar la rama funcional activa**: ubicar la rama funcional (`feature/*`, `fix/*`, `security/*`, etc.) y el worktree donde reside el avance más reciente.
3. **Continuar sobre esa rama cuando corresponda**: trabajar directamente sobre la rama funcional activa si la tarea forma parte del mismo objetivo técnico.
4. **No crear automáticamente otra rama solo porque cambió la herramienta**: el cambio de asistente (por ejemplo, de Codex a Antigravity o viceversa) jamás justifica bifurcar ni renombrar la rama con el nombre del agente.
5. **Crear una rama diferente solamente cuando exista una justificación técnica real**: por ejemplo, aislar una característica independiente o un hotfix que deba integrarse por separado.
6. **Mantener commits pequeños, explicativos y revisables**: seguir el formato Conventional Commits (`feat:`, `fix:`, `security:`, `test:`, `chore:`, `docs:`).
7. **Respetar el flujo de Pull Requests y los responsables del equipo**: coordinar las integraciones respetando la autoría y revisiones de **Ivan** y **Brayan**.

---

## 3. Protección de los Repositorios

- **No eliminar trabajo ajeno**: nunca borrar ramas locales o remotas, commits ni worktrees creados por otros miembros del equipo o sesiones previas sin autorización expresa.
- **No modificar `main` directamente**: `main` está reservada para código validado y promovido mediante Pull Request.
- **Integrar a `develop` (o rama base de integración) mediante PR cuando corresponda**: toda promoción debe pasar por revisión de código.
- **Comprobar CI antes de integrar**: verificar que la suite de pruebas (`mvn test` con PostgreSQL aislado) y las validaciones de seguridad pasen al 100% antes de solicitar o aprobar un merge.
- **Preservar trabajo no confirmado**: nunca ejecutar `git reset --hard`, `git checkout -- .`, `git restore .` ni `git clean -fd` sobre árboles de trabajo con modificaciones pendientes.
- **No ejecutar despliegues productivos sin autorización**: no promover cambios a Render ni ejecutar migraciones Flyway sobre la base de datos productiva sin aprobación explícita del propietario.
- **No copiar secretos, keystores o contraseñas a Git**: jamás registrar ni confirmar `JWT_SECRET`, `BREVO_API_KEY`, `TWO_FACTOR_ENCRYPTION_KEY`, credenciales de PostgreSQL, tokens JWT, códigos OTP, archivos `.env` ni almacenes `.p12`/`.jks`.
- **No mezclar cambios entre repositorios**: este repositorio contiene exclusivamente el backend Spring Boot; no introducir aquí código de la app móvil Ionic/Capacitor ni del frontend web Angular.

---

## 4. Referencia Rápida del Repositorio Backend

- **Rama funcional activa de cierre MFA/Auth**: `fix/backend-auth-mfa-integration` (worktree `.local-worktrees/antigravity-cierre-produccion-backend`).
- **Rama funcional previa de contratos Auth**: `fix/backend-auth-contracts` (worktree `.local-worktrees/cierre-android`).
- **Guía detallada de flujo Git**: consultar [`docs/GIT_WORKFLOW.md`](docs/GIT_WORKFLOW.md).
