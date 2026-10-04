# LSHOE Store

Spring Boot + Thymeleaf storefront with a separate FastAPI/PyTorch AI service and PostgreSQL.

## Runtime modes

### Local / IntelliJ

Spring Boot runs at `http://localhost:8081`. The dev profile can prepare and start the local
FastAPI service at `http://127.0.0.1:8001`. Copy `.env.example` to `.env`, then fill in the
database and optional integration settings. Ngrok/Cloudflare helpers are local-only.

### Vercel production

The same repository can be connected to two Vercel projects:

- **WEB project** — Root Directory `/`, built from `Dockerfile.vercel`.
- **AI project** — Root Directory `ai-service`, built from `ai-service/Dockerfile.vercel`.

Persistent data lives in PostgreSQL. Product images uploaded by administrators are stored in
PostgreSQL in production because Vercel container instances are stateless.

Required WEB environment variables:

```text
DATABASE_URL=postgresql://...
AI_SERVICE_URL=https://<your-ai-project>.vercel.app
AI_INTERNAL_API_KEY=<long-random-secret>
```

Required AI environment variables:

```text
DATABASE_URL=postgresql://...
AI_INTERNAL_API_KEY=<same-secret-as-web>
AI_STORE_BASE_URL=https://<your-web-project>.vercel.app
```

`AI_INTERNAL_API_KEY` is a secret you create yourself. The value must match exactly in the WEB
and AI projects. Redeploy both projects after changing environment variables.

Health endpoints for the AI service:

- `/live` checks whether the FastAPI process is running.
- `/ready` checks whether the service can reach PostgreSQL.
- `/health` is retained as a compatibility alias of `/ready`.

## Database migrations

Flyway uses:

```text
src/main/resources/db/migration/V1__baseline_and_integrity.sql
```

Do not edit V1 after a shared/production database has been created from it. Future schema
changes should be added as V2, V3, and so on.
