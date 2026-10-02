#!/bin/sh
set -eu

PUBLIC_PORT="${PORT:-80}"
AI_INTERNAL_PORT="${AI_INTERNAL_PORT:-8001}"

echo "[vercel-ai] Opening public port ${PUBLIC_PORT} immediately."
echo "[vercel-ai] Forwarding ${PUBLIC_PORT} -> 127.0.0.1:${AI_INTERNAL_PORT}."

socat \
  "TCP-LISTEN:${PUBLIC_PORT},reuseaddr,fork" \
  "TCP:127.0.0.1:${AI_INTERNAL_PORT},retry=60,interval=1" &
BRIDGE_PID=$!

echo "[vercel-ai] TCP bridge PID: ${BRIDGE_PID}"
echo "[vercel-ai] Starting FastAPI on internal port ${AI_INTERNAL_PORT}."

exec python -m uvicorn app.main:app \
  --host 0.0.0.0 \
  --port "${AI_INTERNAL_PORT}"
