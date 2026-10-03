# LSHOE Store

Spring Boot + Thymeleaf storefront with a FastAPI/PyTorch AI service and PostgreSQL.

## Supported modes

- **Local / IntelliJ + ngrok:** Spring Boot starts FastAPI locally on `127.0.0.1:8001`.
- **SnapDeploy production:** two Small containers, one for Spring and one for AI, both using Neon PostgreSQL.

The production split avoids the exit-code-137/OOM problem caused by running the JVM and PyTorch inside the same 512 MB container.

## Local development

```powershell
Copy-Item .env.local.example .env
```

Edit the local database credentials, then run `LshoeStoreApplication` from IntelliJ. The default `dev` profile keeps AI autostart and ngrok autostart enabled.

For a fixed ngrok URL used by verification/reset links:

```env
NGROK_PUBLIC_URL=https://your-name.ngrok-free.app
```

## Database

Production uses Neon through `DATABASE_URL`. The full Neon URI may contain the username/password; separate `DB_USERNAME` and `DB_PASSWORD` are not required in that case.

Flyway is currently squashed to one migration:

```text
src/main/resources/db/migration/V1__baseline_and_integrity.sql
```

The current Neon database should already have been created from this V1. Do not edit V1 again after it has been applied; future schema changes must be V2, V3, and so on.

## SnapDeploy

See [DEPLOY-SNAPDEPLOY.md](DEPLOY-SNAPDEPLOY.md) for the exact two-container setup and environment variables.

Important points:

- `main` deploys the web container.
- `ai-deploy` deploys the AI container.
- GitHub Actions mirrors `main` to `ai-deploy`, so normal development still uses one `git push origin main`.
- The WEB and AI containers must use the same `AI_INTERNAL_API_KEY`.
- Both containers use the same Neon `DATABASE_URL`.
- Do not set SnapDeploy's managed `PORT` variable yourself.

## Normal update workflow

```bash
git add .
git commit -m "update"
git push origin main
```

SnapDeploy rebuilds the web from `main`; GitHub Actions syncs `ai-deploy`, which triggers the AI rebuild as well.
