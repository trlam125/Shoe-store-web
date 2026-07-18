"""Optional standalone chatbot API.

Run locally only:
    uvicorn api:app --host 127.0.0.1 --port 8000

The Spring Boot application already has its own /chatbot/chat endpoint, so this
service is only kept for independent experiments.
"""

import os

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, field_validator
from openai import OpenAI

load_dotenv()

app = FastAPI(title="LSHOE Chatbot API")

SYSTEM_PROMPT = (
    "Bạn là trợ lý tư vấn giày của LSHOE. Hãy giúp khách hàng chọn giày, "
    "tư vấn size và phối đồ. Trả lời ngắn gọn, thân thiện bằng tiếng Việt."
)


class ChatMessage(BaseModel):
    role: str
    content: str = Field(min_length=1, max_length=2_000)

    @field_validator("role")
    @classmethod
    def validate_role(cls, value: str) -> str:
        if value not in {"user", "assistant"}:
            raise ValueError("role must be user or assistant")
        return value


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2_000)
    history: list[ChatMessage] = Field(default_factory=list, max_length=10)


class ChatResponse(BaseModel):
    reply: str


@app.post("/api/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    api_key = os.getenv("NVIDIA_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="NVIDIA_API_KEY is not configured")

    client = OpenAI(base_url="https://integrate.api.nvidia.com/v1", api_key=api_key)
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend(item.model_dump() for item in req.history[-10:])
    messages.append({"role": "user", "content": req.message.strip()})

    try:
        response = client.chat.completions.create(
            model=os.getenv("CHATBOT_MODEL", "openai/gpt-oss-120b"),
            messages=messages,
            temperature=0.7,
            max_tokens=1_024,
            stream=False,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Chat provider is unavailable") from exc

    return ChatResponse(reply=response.choices[0].message.content or "")
