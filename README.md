# LSHOE Store

Spring Boot + Thymeleaf storefront with an internal FastAPI/PyTorch AI service.

The repository supports three run modes without maintaining separate code branches:

1. **Local IntelliJ**: Spring Boot runs on `8081` and automatically starts FastAPI on `127.0.0.1:8001`.
2. **Local + ngrok**: ngrok exposes only Spring Boot; AI remains local/private.
3. **Northflank**: one Combined Service/container runs both Spring Boot and FastAPI. Only Spring Boot port `8081` is public.

## Architecture

### Northflank

```text
Internet
   |
   v
Spring Boot :8081  (PUBLIC)
   |
   +---- http://127.0.0.1:8001 ----> FastAPI AI (LOOPBACK ONLY)
   |
   +----> PostgreSQL addon
```

FastAPI is deliberately bound to `127.0.0.1`. Browser requests never call port `8001` directly. The frontend calls Spring endpoints such as `/ai/image-search/analyze`, and Spring forwards the work internally to FastAPI.

### Local / ngrok

```text
Browser / ngrok
      |
      v
Spring Boot :8081
      |
      +----> FastAPI :8001
      |
      +----> local PostgreSQL
```

## Local development

### 1. PostgreSQL

Start the included local database:

```bash
docker compose up -d postgres
```

Default local connection:

```text
Database : lshoe_store
User     : postgres
Password : postgres
Port     : 5432
```

### 2. Local environment

Copy `.env.example` to `.env`. Only values that are normally edited locally are kept there:

```env
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/lshoe_store
DB_USERNAME=postgres
DB_PASSWORD=postgres
NVIDIA_API_KEY=
MAIL_HOST=smtp.gmail.com
MAIL_USERNAME=
MAIL_PASSWORD=
```

Spring already defaults to the `dev` profile on local runs, port `8081`, FastAPI at `127.0.0.1:8001`, AI auto-start, and ngrok auto-start. Those defaults therefore do not need to be repeated in `.env`.

When the application is started from IntelliJ, Spring will prepare the root `.venv` if needed and start `ai-service` automatically.

### 3. Run from IntelliJ

Run:

```text
src/main/java/com/example/lshoestore/LshoeStoreApplication.java
```

Website:

```text
http://localhost:8081
```

AI health endpoints are local only:

```text
http://127.0.0.1:8001/live
http://127.0.0.1:8001/ready
```

## Local + ngrok

The existing ngrok workflow is preserved.

Configure your ngrok token once:

```bash
ngrok config add-authtoken YOUR_TOKEN
```

Then either keep:

```env
NGROK_TUNNEL_AUTOSTART=true
```

and run the Spring application from IntelliJ, or use `run-ngrok.bat` as before.

ngrok exposes only Spring Boot `8081`. External users can still use every AI-backed feature because Spring calls FastAPI internally on `127.0.0.1:8001`.

## Deploy to Northflank as ONE service

The old two-service/Vercel deployment layout is not used. Deploy the repository root as one Northflank Combined Service.

### 1. Push the repository to GitHub

The root must contain:

```text
Dockerfile
docker-entrypoint.sh
pom.xml
src/
ai-service/
```

Do not commit `.env`.

### 2. Create a Northflank project

Create one project, for example:

```text
lshoe-store
```

### 3. Create PostgreSQL

Inside the same Northflank project, create one PostgreSQL addon.

Keep the database private. Create/inherit a secret that exposes the addon's PostgreSQL URI to the service as:

```text
DATABASE_URL
```

The application accepts a normal managed PostgreSQL URI such as:

```text
postgresql://user:password@host:5432/database
```

Spring converts it to JDBC internally; FastAPI uses the same value directly.

### 4. Create ONE Combined Service

Create a service from the GitHub repository with:

```text
Build type     : Dockerfile
Build context  : repository root
Dockerfile     : /Dockerfile
```

You do **not** create a separate `lshoe-ai` service.

### 5. Networking

Create one public HTTP port:

```text
Port       : 8081
Protocol   : HTTP
Visibility : Public
```

Do not expose port `8001`. FastAPI binds to loopback inside the container and is intentionally inaccessible from the public network.

Recommended Spring health check:

```text
GET /health
```

### 6. Runtime variables

The Docker image already includes the production process settings. The only infrastructure variable normally required is:

```env
DATABASE_URL=<Northflank PostgreSQL URI>
```

Optional application secrets/settings:

```env
NVIDIA_API_KEY=
MAIL_HOST=smtp.gmail.com
MAIL_USERNAME=
MAIL_PASSWORD=
BOOTSTRAP_ADMIN_EMAIL=
BOOTSTRAP_ADMIN_PASSWORD=
```

You normally do not need to set AI process variables on Northflank. The Docker image already keeps FastAPI on `127.0.0.1:8001` and Spring communicates with it internally.

### 7. Public URL

Northflank injects its public hostname using `NF_HOSTS`/`NF_HOSTS_CUSTOM`. Spring automatically uses that hostname for verification and password-reset links when `APP_PUBLIC_BASE_URL` is blank.

For a custom domain, you can explicitly set:

```env
APP_PUBLIC_BASE_URL=https://your-domain.example
```

## Container behavior

`docker-entrypoint.sh` starts:

```text
FastAPI  -> 127.0.0.1:8001
Spring   -> 0.0.0.0:8081
```

Both processes belong to the same container lifecycle. If either Spring or FastAPI exits, the entrypoint terminates the other process so Northflank can restart the service cleanly.

## Important resource note

Spring Boot and PyTorch now share the RAM of one container. The image uses CPU-only PyTorch and limits JVM heap proportionally, but image-search workloads are still the heaviest part of the application. If Northflank reports `OOMKilled`/exit code `137`, the issue is container memory pressure; increase memory or reduce the AI workload/model footprint.

## Security notes

- Only port `8081` should be public.
- Never publish port `8001`.
- Do not commit `.env`, mail passwords, database credentials, or API keys.
- Product uploads use PostgreSQL storage in production because container filesystems are disposable.
- Registration uses the built-in server-side CAPTCHA and rate limiting.
