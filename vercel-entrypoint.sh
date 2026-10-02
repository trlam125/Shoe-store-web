#!/usr/bin/env bash
set -euo pipefail

# Vercel assigns the public HTTP port through PORT (80 by default). Spring Boot
# is the only public server; FastAPI stays on loopback inside the same container.
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
export SERVER_PORT="${PORT:-${SERVER_PORT:-80}}"
export AI_SERVICE_AUTOSTART=false
export AI_SERVICE_SETUP_VENV=false
export AI_SERVICE_URL="${AI_SERVICE_URL:-http://127.0.0.1:8001}"
export AI_HOST=127.0.0.1
export AI_PORT=8001
export AI_RELOAD=false
export PRODUCT_IMAGE_STORAGE="${PRODUCT_IMAGE_STORAGE:-database}"
export AI_MAX_UPLOAD_BYTES="${AI_MAX_UPLOAD_BYTES:-4000000}"

# The AI image retriever can safely fetch the storefront's relative image URLs
# through the loopback Spring server. No public callback URL is required.
export AI_STORE_BASE_URL="${AI_STORE_BASE_URL:-http://127.0.0.1:${SERVER_PORT}}"
export AI_TRUSTED_IMAGE_ORIGINS="${AI_TRUSTED_IMAGE_ORIGINS:-${AI_STORE_BASE_URL}}"

# Use Vercel's generated production domain for verification/reset links unless a
# custom APP_PUBLIC_BASE_URL was explicitly configured.
if [[ -z "${APP_PUBLIC_BASE_URL:-}" ]]; then
    if [[ -n "${VERCEL_PROJECT_PRODUCTION_URL:-}" ]]; then
        export APP_PUBLIC_BASE_URL="https://${VERCEL_PROJECT_PRODUCTION_URL}"
    elif [[ -n "${VERCEL_URL:-}" ]]; then
        export APP_PUBLIC_BASE_URL="https://${VERCEL_URL}"
    fi
fi

# Managed PostgreSQL providers commonly expose DATABASE_URL as
# postgresql://user:pass@host/db. Spring JDBC needs jdbc:postgresql://..., while
# the Python service keeps the original PostgreSQL URL in AI_DATABASE_URL.
if [[ "${DATABASE_URL:-}" == postgresql://* || "${DATABASE_URL:-}" == postgres://* ]]; then
    export AI_DATABASE_URL="${AI_DATABASE_URL:-${DATABASE_URL}}"
    eval "$(python - <<'PY'
import os
import shlex
from urllib.parse import unquote, urlsplit

value = os.environ["DATABASE_URL"]
parsed = urlsplit(value)
host = parsed.hostname or ""
if ":" in host and not host.startswith("["):
    host = f"[{host}]"
port = f":{parsed.port}" if parsed.port else ""
normalized_query = parsed.query.replace("channel_binding=", "channelBinding=")
query = f"?{normalized_query}" if normalized_query else ""
jdbc = f"jdbc:postgresql://{host}{port}{parsed.path}{query}"
print("export DATABASE_URL=" + shlex.quote(jdbc))
if parsed.username and not os.environ.get("DB_USERNAME"):
    print("export DB_USERNAME=" + shlex.quote(unquote(parsed.username)))
if parsed.password is not None and not os.environ.get("DB_PASSWORD"):
    print("export DB_PASSWORD=" + shlex.quote(unquote(parsed.password)))
PY
)"
fi

cleanup() {
    if [[ -n "${JAVA_PID:-}" ]]; then kill -TERM "${JAVA_PID}" 2>/dev/null || true; fi
    if [[ -n "${AI_PID:-}" ]]; then kill -TERM "${AI_PID}" 2>/dev/null || true; fi
}
trap cleanup TERM INT EXIT

# Start Spring Boot first. It is the public HTTP server that Vercel routes to.
cd /app
java -jar app.jar &
JAVA_PID=$!

echo "[vercel] Spring Boot PID: ${JAVA_PID}"
echo "[vercel] Waiting for Spring Boot on port ${SERVER_PORT}..."

SPRING_READY=false
for _ in $(seq 1 120); do
    # If Java exits before opening the public port, surface the real exit code.
    if ! kill -0 "${JAVA_PID}" 2>/dev/null; then
        echo "[vercel] ERROR: Spring Boot exited during startup."
        set +e
        wait "${JAVA_PID}"
        STATUS=$?
        exit "${STATUS}"
    fi

    # Bash /dev/tcp keeps the runtime image small and avoids requiring curl.
    if (echo >"/dev/tcp/127.0.0.1/${SERVER_PORT}") >/dev/null 2>&1; then
        SPRING_READY=true
        echo "[vercel] Spring Boot is listening on port ${SERVER_PORT}."
        break
    fi

    sleep 0.5
done

if [[ "${SPRING_READY}" != "true" ]]; then
    echo "[vercel] ERROR: Spring Boot did not open port ${SERVER_PORT} within 60 seconds."
    kill -TERM "${JAVA_PID}" 2>/dev/null || true
    set +e
    wait "${JAVA_PID}" 2>/dev/null
    exit 1
fi

# Start FastAPI only after the public website is ready. FastAPI is an internal
# companion service; an AI-service failure must not take the storefront down.
cd /app/ai-service
python run.py &
AI_PID=$!
echo "[vercel] FastAPI PID: ${AI_PID}"

cd /app

# Spring Boot owns the lifecycle of the public container. Keep the container
# alive as long as Spring is alive, even if FastAPI exits unexpectedly.
set +e
wait "${JAVA_PID}"
STATUS=$?

echo "[vercel] Spring Boot stopped with status ${STATUS}."

if [[ -n "${AI_PID:-}" ]] && kill -0 "${AI_PID}" 2>/dev/null; then
    kill -TERM "${AI_PID}" 2>/dev/null || true
fi
wait "${AI_PID:-}" 2>/dev/null || true

exit "${STATUS}"
