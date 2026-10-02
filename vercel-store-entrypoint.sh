#!/bin/sh
set -eu

PUBLIC_PORT="${PORT:-80}"
SPRING_PORT="${SPRING_INTERNAL_PORT:-8081}"

echo "[vercel-store] Opening public port ${PUBLIC_PORT} immediately."
echo "[vercel-store] Forwarding ${PUBLIC_PORT} -> 127.0.0.1:${SPRING_PORT}."

# Open the Vercel-facing port before Spring finishes creating its application
# context. For each incoming connection, retry the Spring backend while it is
# still starting instead of leaving Vercel with no listening TCP port.
socat \
  "TCP-LISTEN:${PUBLIC_PORT},reuseaddr,fork" \
  "TCP:127.0.0.1:${SPRING_PORT},retry=60,interval=1" &
SOCAT_PID=$!

echo "[vercel-store] TCP bridge PID: ${SOCAT_PID}"
echo "[vercel-store] Starting Spring Boot on internal port ${SPRING_PORT}."

# Command-line properties have higher precedence than PORT/SERVER_PORT, so
# Spring cannot accidentally compete with the public socat listener.
exec java -jar /app/app.jar \
  --server.address=0.0.0.0 \
  --server.port="${SPRING_PORT}"
