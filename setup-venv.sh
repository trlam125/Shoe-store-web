#!/usr/bin/env sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VENV=${LSHOE_VENV_DIR:-"$PROJECT_ROOT/.venv"}
REQUIREMENTS=${1:-"$PROJECT_ROOT/requirements.txt"}

if [ ! -f "$REQUIREMENTS" ]; then
    echo "[ERROR] Requirements file not found: $REQUIREMENTS" >&2
    exit 1
fi

is_supported_python() {
    "$1" -c 'import sys; raise SystemExit(0 if sys.version_info[:2] in ((3,10),(3,11),(3,12),(3,13)) else 1)' >/dev/null 2>&1
}

if [ -x "$VENV/bin/python" ]; then
    if ! is_supported_python "$VENV/bin/python"; then
        echo "[ERROR] The virtual environment must use Python 3.10 through 3.13." >&2
        echo "Delete $VENV and run setup-venv.sh again." >&2
        exit 1
    fi
else
    PYTHON=""
    for candidate in python3.12 python3.13 python3.11 python3.10 python3 python; do
        if command -v "$candidate" >/dev/null 2>&1 && is_supported_python "$candidate"; then
            PYTHON=$candidate
            break
        fi
    done
    if [ -z "$PYTHON" ]; then
        echo "[ERROR] Python 3.10, 3.11, 3.12 or 3.13 was not found." >&2
        exit 1
    fi
    echo "[INFO] Creating virtual environment at $VENV"
    "$PYTHON" -m venv "$VENV"
fi

"$VENV/bin/python" --version
"$VENV/bin/python" -m pip install -r "$REQUIREMENTS"
