# LSHOE Store

Spring Boot + Thymeleaf storefront with a separate FastAPI/PyTorch AI service and PostgreSQL.

## Runtime modes

### Local / IntelliJ

Spring Boot runs at `http://localhost:8081`. The dev profile can prepare/start the local
FastAPI service at `http://127.0.0.1:8001`. ngrok/Cloudflare tunnel helpers remain local-only.
Copy `.env.local.example` to `.env` for local settings.

### Vercel production

The same Git repository is connected to **two Vercel projects**:

- **WEB project** — Root Directory `/`, built from `Dockerfile.vercel`.
- **AI project** — Root Directory `ai-service`, built from `ai-service/Dockerfile.vercel`.

Both projects can track the same `main` branch. Each Git push creates deployments for both
connected Vercel projects, so no mirror branch or GitHub Action is required.

Persistent data lives in Neon PostgreSQL. Product images uploaded by administrators are stored
in PostgreSQL in production because Vercel container instances are stateless.

See [`DEPLOY-VERCEL.md`](DEPLOY-VERCEL.md) for deployment and environment-variable setup.

## Database migrations

Flyway is enabled and the current reset schema is represented by the single migration:

```text
src/main/resources/db/migration/V1__baseline_and_integrity.sql
```

Do not edit V1 after a shared/production database has been created from it. Future schema changes
must be added as V2, V3, and so on.
