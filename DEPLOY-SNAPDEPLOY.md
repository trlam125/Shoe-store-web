# SnapDeploy: 2-container deployment

This repository intentionally deploys as two Small containers so Spring Boot and PyTorch do not share the same 512 MB RAM limit.

## Layout

```text
GitHub repository
  main      -> lshoe-web  (root /, Dockerfile)
  ai-deploy -> lshoe-ai   (root /ai-service, Dockerfile)

lshoe-web --HTTPS + X-Internal-API-Key--> lshoe-ai
     |                                      |
     +---------------- Neon ----------------+
```

The workflow `.github/workflows/sync-ai-deploy.yml` mirrors every push to `main` into `ai-deploy`. You only push `main` during normal development.

## WEB container

- Repository branch: `main`
- Root directory: `/`
- Dockerfile: `Dockerfile`
- Build context: `.`
- Start command: blank
- Port: auto

Runtime variables:

```env
DATABASE_URL=postgresql://USER:PASSWORD@HOST/DB?sslmode=require
AI_SERVICE_URL=https://YOUR-AI.containers.snapdeploy.app
AI_INTERNAL_API_KEY=THE_SAME_LONG_RANDOM_SECRET
APP_PUBLIC_BASE_URL=https://YOUR-WEB.containers.snapdeploy.app
```

Optional:

```env
NVIDIA_API_KEY=
MAIL_HOST=smtp.gmail.com
MAIL_USERNAME=
MAIL_PASSWORD=
```

Do not set `PORT`; SnapDeploy owns it.

## AI container

Create this only after the `ai-deploy` branch exists.

- Repository branch: `ai-deploy`
- Root directory: `/ai-service`
- Dockerfile: `Dockerfile`
- Build context: `.`
- Start command: blank
- Port: auto

Runtime variables:

```env
DATABASE_URL=postgresql://USER:PASSWORD@HOST/DB?sslmode=require
AI_INTERNAL_API_KEY=THE_SAME_LONG_RANDOM_SECRET
AI_STORE_BASE_URL=https://YOUR-WEB.containers.snapdeploy.app
AI_TRUSTED_IMAGE_ORIGINS=https://YOUR-WEB.containers.snapdeploy.app
AI_ALLOWED_ORIGINS=https://YOUR-WEB.containers.snapdeploy.app
```

## Free-tier sleep behavior

A sleeping SnapDeploy container is not woken by a server-to-server request. If `lshoe-ai` is asleep, open this in a browser first and wait for it to wake:

```text
https://YOUR-AI.containers.snapdeploy.app/live
```

Once AI is awake, Spring-to-AI requests work normally until it sleeps again.

## Local + ngrok

Local behavior is unchanged. Copy `.env.local.example` to `.env`, run Spring Boot from IntelliJ, and the dev profile starts FastAPI on `127.0.0.1:8001`. ngrok remains enabled by the dev profile.
