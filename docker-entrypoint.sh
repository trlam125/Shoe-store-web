#!/usr/bin/env bash
set -uo pipefail

AI_PID=""
JAVA_PID=""
STOPPING=0
WEB_PORT="${PORT:-${SERVER_PORT:-8081}}"

# Keep all internal callbacks aligned with SnapDeploy's managed web port.
export SERVER_PORT="$WEB_PORT"
export AI_STORE_BASE_URL="${AI_STORE_BASE_URL:-http://127.0.0.1:${WEB_PORT}}"
export AI_TRUSTED_IMAGE_ORIGINS="${AI_TRUSTED_IMAGE_ORIGINS:-${AI_STORE_BASE_URL}}"

shutdown() {
    if [[ "$STOPPING" -eq 1 ]]; then
        return
    fi
    STOPPING=1

    if [[ -n "$JAVA_PID" ]] && kill -0 "$JAVA_PID" 2>/dev/null; then
        kill -TERM "$JAVA_PID" 2>/dev/null || true
    fi
    if [[ -n "$AI_PID" ]] && kill -0 "$AI_PID" 2>/dev/null; then
        kill -TERM "$AI_PID" 2>/dev/null || true
    fi

    [[ -n "$JAVA_PID" ]] && wait "$JAVA_PID" 2>/dev/null || true
    [[ -n "$AI_PID" ]] && wait "$AI_PID" 2>/dev/null || true
}

trap shutdown TERM INT

cd /app/ai-service
/opt/ai-venv/bin/python -m uvicorn app.main:app \
    --host "${AI_HOST:-127.0.0.1}" \
    --port "${AI_PORT:-8001}" \
    --workers 1 &
AI_PID=$!

echo "[startup] FastAPI AI started on ${AI_HOST:-127.0.0.1}:${AI_PORT:-8001} (pid=$AI_PID)"

cd /app
java -jar /app/app.jar &
JAVA_PID=$!

echo "[startup] Spring Boot started on 0.0.0.0:${WEB_PORT} (pid=$JAVA_PID)"

# The deployment is healthy only while both processes are alive. If either one
# exits, stop the other and let the container platform restart the whole unit.
wait -n "$AI_PID" "$JAVA_PID"
EXIT_CODE=$?

echo "[shutdown] One application process exited with code $EXIT_CODE; stopping container."
shutdown
exit "$EXIT_CODE"
