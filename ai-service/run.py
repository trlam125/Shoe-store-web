import os
from pathlib import Path

import uvicorn
from dotenv import dotenv_values

SERVICE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SERVICE_DIR.parent

# Merge project and service files while keeping explicit OS/process variables highest priority.
file_environment = {
    **dotenv_values(PROJECT_ROOT / ".env"),
    **dotenv_values(SERVICE_DIR / ".env"),
}
for key, value in file_environment.items():
    if value is not None:
        os.environ.setdefault(key, value)

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=os.getenv("AI_HOST", "127.0.0.1"),
        port=int(os.getenv("AI_PORT", "8001")),
        reload=os.getenv("AI_RELOAD", "true").lower() == "true",
    )
