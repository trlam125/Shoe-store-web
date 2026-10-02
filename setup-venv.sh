#!/usr/bin/env sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VENV=${LSHOE_VENV_DIR:-"$PROJECT_ROOT/.venv"}
REQUIREMENTS=${1:-"$PROJECT_ROOT/requirements.txt"}

if [ ! -f "$REQUIREMENTS" ]; then
    echo "[ERROR] Requirements file not found: $REQUIREMENTS" >&2
    exit 1
fi

if [ ! -x "$VENV/bin/python" ]; then
    PYTHON_CMD=""
    for candidate in python3.12 python3.13 python3.11 python3.10 python3 python; do
        if command -v "$candidate" >/dev/null 2>&1; then
            if "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info[:2] in ((3,10),(3,11),(3,12),(3,13)) else 1)' >/dev/null 2>&1; then
                PYTHON_CMD=$candidate
                break
            fi
        fi
    done

    if [ -z "$PYTHON_CMD" ]; then
        echo "[ERROR] Python 3.10, 3.11, 3.12 or 3.13 was not found." >&2
        exit 1
    fi

    echo "[INFO] Creating virtualenv at $VENV using $PYTHON_CMD"
    "$PYTHON_CMD" -m venv "$VENV"
fi

"$VENV/bin/python" --version
"$VENV/bin/python" -m pip install -r "$REQUIREMENTS"
