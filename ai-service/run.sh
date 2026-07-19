#!/usr/bin/env sh
set -eu

SERVICE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SERVICE_DIR/.." && pwd)
VENV=${LSHOE_VENV_DIR:-"$PROJECT_ROOT/.venv"}

if [ "${LSHOE_SKIP_VENV_SETUP:-false}" != "true" ]; then
    "$PROJECT_ROOT/setup-venv.sh" "$SERVICE_DIR/requirements.txt"
fi

if [ ! -x "$VENV/bin/python" ]; then
    echo "[ERROR] Python was not found in virtualenv: $VENV" >&2
    echo "Run setup-venv.sh from the project root." >&2
    exit 1
fi

cd "$SERVICE_DIR"
exec "$VENV/bin/python" run.py
