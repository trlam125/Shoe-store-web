# LSHOE Store

Spring Boot + Thymeleaf storefront with a FastAPI AI service. This repository is prepared for two modes:

- **Local/IntelliJ + ngrok:** Spring Boot starts FastAPI locally as before.
- **SnapDeploy (one container):** one Docker container runs Spring Boot publicly and FastAPI only on loopback.

## Production layout on SnapDeploy

```text
Browser
   |
   v
SnapDeploy HTTPS
   |
   v
Spring Boot 0.0.0.0:$PORT
   |
   +----> FastAPI 127.0.0.1:8001
   |
   +----> Neon PostgreSQL
```

FastAPI is not exposed to the Internet in the one-container deployment.

## 1. Local development

Copy the sample environment file:

```powershell
Copy-Item .env.example .env
```

Edit at least the local PostgreSQL values in `.env`:

```env
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/lshoe_store
DB_USERNAME=postgres
DB_PASSWORD=your_password
```

Then run `LshoeStoreApplication` from IntelliJ. In the default `dev` profile:

- Spring Boot listens on `8081` unless `SERVER_PORT` is set.
- Spring Boot prepares/starts the local FastAPI service on `8001`.
- ngrok auto-start remains enabled by the dev profile.

For verification/password-reset links through a fixed ngrok domain, set:

```env
NGROK_PUBLIC_URL=https://your-name.ngrok-free.app
```

The helper `run-ngrok.bat` is still available for manual tunnel use.

## 2. Neon PostgreSQL

SnapDeploy has no persistent disk on the free container, so production data should stay in Neon.

Use the full Neon PostgreSQL connection string as `DATABASE_URL`, for example:

```text
postgresql://USER:PASSWORD@HOST/DB?sslmode=require
```

The Java bootstrap converts this automatically to a JDBC URL. FastAPI uses the same PostgreSQL URI directly.

If you already imported the local `lshoe_store` dump into Neon, use that database. Otherwise Flyway will create/validate the schema when Spring Boot starts.

## 3. Deploy one container to SnapDeploy

1. Push this repository to GitHub.
2. In SnapDeploy choose **Deploy from GitHub**.
3. Select the repository and the `main` branch.
4. Keep the repository root as the app root; SnapDeploy will use the root `Dockerfile`.
5. Use the free **Small** container for the first test.
6. Do **not** create a second AI container for this version.

SnapDeploy manages `PORT` itself. Do not add or override `PORT` manually. Spring Boot reads `PORT` first and falls back to `SERVER_PORT=8081` only outside SnapDeploy.

The image exposes port `8081` so SnapDeploy can detect the web service, while FastAPI remains on `127.0.0.1:8001`.

## 4. SnapDeploy environment variables

Set these in the SnapDeploy container settings.

### Required

```env
DATABASE_URL=postgresql://USER:PASSWORD@HOST/DB?sslmode=require
APP_PUBLIC_BASE_URL=https://YOUR-CONTAINER.containers.snapdeploy.app
```

`APP_PUBLIC_BASE_URL` is important for email-verification and password-reset links. The default SnapDeploy hostname is based on the container name.

### If email is enabled

```env
MAIL_HOST=smtp.gmail.com
MAIL_USERNAME=your_account@gmail.com
MAIL_PASSWORD=your_app_password
```

### If the NVIDIA chatbot is enabled

```env
NVIDIA_API_KEY=your_key
```

You do **not** need to set these for the one-container deployment because the Dockerfile already provides production-safe values:

```text
SPRING_PROFILES_ACTIVE
AI_SERVICE_URL
AI_SERVICE_AUTOSTART
AI_SERVICE_SETUP_VENV
AI_HOST
AI_PORT
PRODUCT_IMAGE_STORAGE
MANAGED_PROXY_RUNTIME
```

Do not enable ngrok or Cloudflare tunnels inside SnapDeploy. Production disables the local tunnel auto-starters.

## 5. First-deploy checks

After deployment, open:

```text
https://YOUR-CONTAINER.containers.snapdeploy.app/health
```

Then test, in order:

1. Home page
2. Login/register + custom CAPTCHA
3. Database reads/writes
4. Email verification/reset, if configured
5. Admin AI analytics
6. Image search

The runtime log should include lines similar to:

```text
[startup] FastAPI AI started on 127.0.0.1:8001
[startup] Spring Boot started on 0.0.0.0:<PORT>
```

## 6. 512 MB memory note

SnapDeploy Free gives a Small container 512 MB RAM and 0.25 vCPU. This project runs both the JVM and Python in that limit, so this build includes several memory-oriented changes:

- Java heap is capped and Serial GC is used.
- BLAS/OpenMP thread counts are limited to 1.
- FastAPI uses one worker.
- `uvicorn[standard]` extras were removed.
- scikit-learn imports only when segmentation is requested.
- PyTorch/torchvision imports only when image search is first used.
- ResNet18 weights are cached into the image at build time.

This gives the storefront a better chance to boot within 512 MB. **Image search is still the heaviest feature.** If the container is killed with exit code `137`/`OOMKilled` when image search loads ResNet18, use the two-container layout instead (Spring and AI each get their own 512 MB container).

## 7. SnapDeploy free-tier behavior

Free containers sleep after inactivity. A browser visit wakes the one container, which means Spring and FastAPI start together; this avoids the separate-AI wake problem that a two-container free deployment would have.

The filesystem is ephemeral, so administrator product uploads are configured to use PostgreSQL in production (`PRODUCT_IMAGE_STORAGE=database`).

## 8. GitHub auto deploy

When the repository/branch is linked in SnapDeploy, a new push can trigger a new build/deployment. Keep secrets in SnapDeploy environment variables, never in Git.

```bash
git add .
git commit -m "update"
git push origin main
```
