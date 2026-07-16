import os

import uvicorn
from dotenv import load_dotenv

load_dotenv()

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=os.getenv("AI_HOST", "0.0.0.0"),
        port=int(os.getenv("AI_PORT", "8001")),
        reload=os.getenv("AI_RELOAD", "true").lower() == "true",
    )
