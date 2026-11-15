# Flujo de Trabajo Git y Colaboración — Casa de Música Castillo

Este documento establece el estándar de organización Git para los tres repositorios del ecosistema **Casa de Música Castillo** (Backend Spring Boot, Aplicación Móvil Ionic/Angular/Capacitor y Frontend Web Angular).

---

## 1. Convención de Ramas

Las ramas deben nombrar **la intención técnica o funcional del cambio**, nunca la herramienta o el agente utilizado:

| Prefijo | Propósito | Ejemplo válido |
| :--- | :--- | :--- |
| `feature/<funcionalidad>` | Nuevas pantallas, endpoints o flujos de negocio | `feature/mobile-account-security` |
| `fix/<correccion>` | Corrección de defectos, contratos o compatibilidad | `fix/backend-auth-mfa-integration` |
| `security/<control>` | Controles de seguridad, MFA, JWT, rate limiting o dependencias | `security/auth-hardening` |
| `test/<pruebas>` | Pruebas automatizadas unitarias, de integración o E2E | `test/tt05-integracion-aislada` |
| `chore/<mantenimiento>` | Configuración de CI/CD, scripts de soporte o limpieza | `chore/devops-quality-baseline` |
| `docs/<documentacion>` | Documentación técnica, contratos de API o normas de equipo | `docs/git-workflow-rules` |
| `release/<version>` | Preparación de entregas candidatas a producción | `release/1.0.0` |

> **Regla estricta**: Están prohibidos los prefijos de herramientas o modelos de IA (`codex/`, `antigravity/`, `claude/`, `gemini/`, etc.).

---

## 2. Relación entre `main`, `develop` y Ramas de Trabajo

- **`main`**: representa el estado estable y certificado para producción (Render, Vercel y Google Play). Nunca recibe commits directos.
- **`develop`** *(o rama de integración activa del módulo)*: concentra las funcionalidades validadas por el equipo antes de su promoción a `main`.
- **Ramas de trabajo (`feature/*`, `fix/*`, `security/*`, etc.)**: parten de `develop` (o de la rama base acordada en una cadena de PRs), mantienen un alcance acotado y se reintegran mediante Pull Request tras superar CI.

---

## 3. Convenciones de Commits

Se utiliza el formato **Conventional Commits**:

```text
<tipo>(<alcance opcional>): <descripción breve en imperativo>
```

- Tipos permitidos: `feat`, `fix`, `security`, `test`, `chore`, `docs`, `refactor`.
- Cada commit debe ser atómico, autoexplicativo y dejar el repositorio compilando.
- Ningún mensaje ni contenido de commit puede incluir contraseñas, tokens, claves privadas ni datos personales reales.

---

## 4. Flujo de Pull Requests y Revisión de CI

1. **Apertura de PR**: toda rama funcional se publica y se vincula a un Pull Request hacia `develop` (o hacia su rama base si forma parte de una pila secuencial de PRs).
2. **Validación de CI obligatoria**:
   - **Backend**: `mvn test` con PostgreSQL aislado y verificación de migraciones Flyway.
   - **Móvil**: `npm run lint`, `npm run test:coverage`, `npm run test:integration`, `npm run test:regression` y verificación de paquetes Android.
   - **Web**: compilación limpia de Angular y ejecución de pruebas unitarias.
3. **Protección de PRs existentes**: no se debe hacer `push --force` ni renombrar unilateralmente ramas remotas que tengan Pull Requests abiertos sin coordinación previa.

---

## 5. Uso de Worktrees

El proyecto utiliza `git worktree` para aislar contextos de compilación y pruebas sin interferir con cambios en curso:

- Antes de crear un worktree nuevo, ejecutar `git worktree list` para verificar si ya existe uno para la tarea activa.
- Conservar las rutas físicas existentes de los worktrees para no invalidar reportes, scripts o configuraciones locales.
- Al renombrar una rama local dentro de un worktree (`git branch -m <anterior> <nueva>`), no es necesario mover ni renombrar la carpeta física del worktree.

---

## 6. Colaboración entre Ivan y Brayan

- **Respeto de autoría y ramas activas**: las ramas creadas por **Ivan** (`IvanRamirezFrancisco`) y **Brayan** (`Tackalay`) no deben sobrescribirse ni eliminarse.
- **Sincronización**: antes de iniciar una tarea relacionada con un módulo compartido, revisar los PRs abiertos y cerrados recientemente para partir de la revisión validada más reciente.
- **Revisión cruzada**: mantener descripciones claras en los PRs indicando qué endpoints, pantallas y pruebas fueron verificados.

---

## 7. Continuidad de Trabajo entre Herramientas de IA

Cuando una tarea pasa de una herramienta de IA a otra (por ejemplo, de Codex a Antigravity o viceversa):

1. El agente entrante inspecciona `AGENTS.md`, `git status`, `git branch -avv` y `git worktree list`.
2. Identifica la rama funcional activa (por ejemplo, `fix/backend-auth-mfa-integration`, `feature/mobile-account-security` o `fix/web-mfa-step-up`).
3. Continúa agregando commits incrementales sobre esa misma rama funcional sin crear ramas con el nombre del agente.

---

## 8. Procedimiento de Integración, Resolución de Conflictos y Migración de PRs

### Resolución de conflictos
1. Actualizar la rama de trabajo desde la rama base mediante `git fetch origin` y merge controlado (nunca `reset --hard`).
2. Resolver conflictos preservando tanto las garantías de seguridad del backend como las validaciones del cliente.
3. Ejecutar nuevamente la suite completa de pruebas antes de confirmar.

### Migración segura de ramas publicadas con PR abierto
Si una rama histórica publicada en `origin` aún conserva un prefijo antiguo (como `codex/...`) y tiene un PR abierto:
1. **No eliminar ni renombrar la rama remota automáticamente**, ya que GitHub cerraría el PR asociado.
2. Con autorización del propietario:
   - Publicar la nueva rama normalizada: `git push -u origin <rama-normalizada>`.
   - Crear el nuevo PR desde `<rama-normalizada>` hacia la misma rama base (o actualizar las referencias dependientes) conservando la descripción y trazabilidad.
   - Cerrar el PR antiguo dejando un enlace explícito al nuevo PR y, solo entonces, retirar la referencia remota antigua si el propietario lo solicita.
