# ICCP — TIRDO Integrated Communication and Collaboration Platform

Release 1 (Foundation): **SEC** identity, roles and audit, **DIR** staff directory and HR sync, **NTF** notifications,
**COM** announcements and circulars, **FIN** the staff Financial Diary. Built from the v4.0 design package (Docs 01–09).

| Folder | What is in it |
|---|---|
| `backend/` | Spring Boot 3.5 / Java 21 modular monolith, PostgreSQL 16 with Flyway (V1–V7), JDBC (no JPA) |
| `backend/db-model/` | The data model (88 tables) that generates V1–V4, plus the 50-check integrity test suite |
| `frontend/` | Angular 20 app with the ICCP design system, which follows Apple's Human Interface Guidelines |
| `infra/` | Docker Compose for PostgreSQL, Keycloak (realm `tirdo`), MinIO and Mailpit |
| `samples/` | Fictional HR and Finance export files in the Doc 03 §6 formats |
| `scripts/` | Sample generator and API smoke tests (bash and PowerShell) |
| `docs/adr/` | Decisions taken while building R1 |

All people and figures in the demo data are invented. Email addresses use the reserved `.example` domain.

## Run everything in Docker (quickest)

You need JDK 21, Maven, Node.js 22 and Docker Desktop. The backend jar and the web app are built on your machine,
using your local Maven repository and `node_modules`, so Docker only downloads small runtime images
(`eclipse-temurin:21-jre`, `nginx:1.27-alpine`, `postgres:16`, `axllent/mailpit`). The script does all of it:

```powershell
powershell -NoExit -ExecutionPolicy Bypass -File C:\dev\iccp-docker-up.ps1
```

Or step by step:

```powershell
cd C:\dev\iccp\backend; mvn -B -DskipTests package; cd ..\frontend; npx ng build; cd ..; if (!(Test-Path infra\.env)) { Copy-Item infra\.env.example infra\.env }; docker compose -f infra\docker-compose.yml --env-file infra\.env up -d --build
```

Open http://localhost:8090 when the backend log says `Demo data loaded`:

```powershell
docker compose -f C:\dev\iccp\infra\docker-compose.yml --env-file C:\dev\iccp\infra\.env logs -f backend
```

Outgoing email appears in Mailpit at http://localhost:8025. PostgreSQL is on host port 15432, local connections only (not 5432, so it
does not clash with a PostgreSQL already installed on Windows; not 49152 or above, where Windows reserves ports for Hyper-V). After changing code, run the step-by-step command again.
`backend/Dockerfile.ci` and `frontend/Dockerfile.ci` build everything inside Docker for CI servers.

## Run it on Windows for development

You need JDK 21, Maven 3.9, Node.js 22 and Docker Desktop. Open PowerShell in `C:\dev\iccp`.

**1. Start the database and mail viewer in Docker** (first time: copy the environment file)

```powershell
cd C:\dev\iccp; if (!(Test-Path infra\.env)) { Copy-Item infra\.env.example infra\.env }; docker compose -f infra\docker-compose.yml --env-file infra\.env up -d postgres mailpit
```

**2. Start the backend with demo data and development sign-in**

```powershell
cd C:\dev\iccp\backend; mvn spring-boot:run "-Dspring-boot.run.profiles=dev,demo"
```

Flyway creates the schema on first start; the demo profile then loads 22 fictional staff, two Finance exports
(one published, one waiting for review with three rows held for checking), claim queries and notices.

**3. Start the web app** (a second PowerShell window)

```powershell
cd C:\dev\iccp\frontend; npm ci; npm start
```

Open http://localhost:4200 and choose a person. Useful people to try:

| Person | Employee no. | Sees |
|---|---|---|
| Neema Mushi | TR1007 | Staff view: Financial Diary (TZS 3.5M approved, 2.0M paid, 1.5M outstanding), notices to acknowledge |
| Petro Urio | TR1041 | Finance officer: imports, query desk, manual allocations |
| Mwanaisha Said | TR1040 | Finance reviewer: publishes imports (cannot publish her own) |
| Yusufu Chande | TR1061 | Communication officer: writes notices and circulars |
| Grace Massawe | TR1030 | Director, notice approver |
| Eng. Elia Mollel | TR1031 | ICTT: roles and audit trail |
| Esther Kimaro | TR1050 | HR officer: HR imports and acting appointments |

**4. Check the API end to end** (on a freshly seeded database; the default address is the Docker web container,
add `-Base http://localhost:8080` for a backend started with Maven)

```powershell
cd C:\dev\iccp; powershell -ExecutionPolicy Bypass -File scripts\smoke.ps1
```

**Start again from an empty database**

```powershell
docker compose -f infra\docker-compose.yml --env-file infra\.env down -v; docker compose -f infra\docker-compose.yml --env-file infra\.env up -d
```

### If the sign-in page says the ICCP server is not responding

The web app is up but the backend is not. Check, in this order:

1. Docker services are running (`postgres` should show `healthy`):

   ```powershell
   docker compose -f C:\dev\iccp\infra\docker-compose.yml --env-file C:\dev\iccp\infra\.env ps
   ```

2. Nothing else is using port 15432 (the Docker database) or 8080 (the backend run with Maven):

   ```powershell
   Get-NetTCPConnection -LocalPort 15432,8080 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalPort, OwningProcess, @{n='Process';e={(Get-Process -Id $_.OwningProcess).ProcessName}}
   ```

3. The backend log (the Maven window, or `docker compose ... logs backend`) ends with `Demo data loaded`. If it stopped with an error,
   that message says what is missing.

### Signing in through Keycloak instead

Start Keycloak with `docker compose -f infra\docker-compose.yml --env-file infra\.env --profile sso up -d keycloak minio`.
The Compose file imports realm `tirdo` with client `iccp-web` (public, PKCE) and the people above as users
(username = employee number in lower case, password `Iccp-Demo-2026`). Run the backend without the `dev` profile:

```powershell
cd C:\dev\iccp\backend; $env:ICCP_AUTH_MODE="oidc"; mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Keycloak accounts are linked to staff records by email on first sign-in (Doc 05 §7). Keycloak admin console:
http://localhost:8180 (admin / the password in `infra\.env`). Mailpit shows outgoing email at http://localhost:8025
when `ICCP_EMAIL_ENABLED=true`.

## Build and test

```powershell
cd C:\dev\iccp\backend; mvn -B verify
```

```powershell
cd C:\dev\iccp\frontend; npx ng build
```

`mvn verify` runs the unit tests, including `ModuleBoundaryTest`, which fails the build if a module imports another
module's internals or queries another module's schema. Database rules are checked by
`backend/db-model/tests/verify_integrity.sql` (50 checks).

Container images for deployment: `backend/Dockerfile` and `frontend/Dockerfile` (nginx, proxies `/api` to the backend).

## Configuration

Everything has a safe development default in `backend/src/main/resources/application.yml` and is overridden by
environment variables in other environments:

| Variable | Purpose |
|---|---|
| `ICCP_DB_URL`, `ICCP_APP_PASSWORD`, `ICCP_OWNER_PASSWORD` | Database connection; the app connects as `iccp_app` (row-level security applies), Flyway as `iccp_owner` |
| `ICCP_AUTH_MODE` | `oidc` (default) or `dev`; `dev` refuses to start under the `prod` profile |
| `ICCP_OIDC_ISSUER` | Keycloak realm URL |
| `ICCP_PUBLIC_URL` | Base URL used in notification links |
| `ICCP_FILE_STORE`, `ICCP_MINIO_*` | Attachment storage (`local` or `minio`) |
| `ICCP_EMAIL_ENABLED`, `ICCP_SMTP_HOST` | Outgoing email (off by default: messages are logged) |

## What R1 covers

* **Announcements:** bilingual notices, events, circulars and directives; approval for circulars and directives;
  scheduled publication and expiry; acknowledgement with reminders at 2 and 4 days and escalation to line managers;
  amendments kept as versions; reach by unit; emergency alerts by SMS.
* **Financial Diary:** each person sees only their own published claims, payments and balance (row-level security in
  PostgreSQL as well as in the API); claim queries with a 3-working-day reply standard, two reopenings, then escalation.
* **Finance workspace:** CSV import with checksum (same file twice is ignored), trailer control totals, rows that fail a rule
  held for checking with a reason, maker-checker publication, manual allocations with a second approver, liability overview.
* **Directory:** search, contact cards, organisation outline, acting appointments, nightly HR import with an exception queue.
* **Notifications:** in-app inbox; email and SMS by category with quiet hours; emergency and security always delivered.
* **Security and audit:** role grants with end dates and approval evidence for privileged roles; hash-chained audit trail
  with a one-click integrity check.
* **Interface:** light and dark appearance, increased contrast, text size, reduced motion, English and Kiswahili,
  keyboard access and screen-reader labels; works from 360 px phones to desktop.

Known gaps are listed in `docs/R1-status.md`.
