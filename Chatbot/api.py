"""
FastAPI server — expose POST /api/chat để Spring Boot gọi vào.
Chạy: uvicorn api:app --host 0.0.0.0 --port 8000 --reload
"""

import os
from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from openai import OpenAI

load_dotenv()

app = FastAPI(title="LSHOE Chatbot API")

# Cho phép Spring Boot (localhost:8081) gọi vào
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:8081"],
    allow_methods=["POST"],
    allow_headers=["Content-Type"],
)

client = OpenAI(
    base_url="https://integrate.api.nvidia.com/v1",
    api_key=os.getenv("NVIDIA_API_KEY"),
)

SYSTEM_PROMPT = (
    "Bạn là trợ lý tư vấn giày của LSHOE — cửa hàng sneaker nam nữ tại Hà Nội. "
    "Hãy giúp khách hàng tìm giày phù hợp, tư vấn size, phong cách phối đồ và "
    "các chương trình khuyến mãi. Trả lời ngắn gọn, thân thiện bằng tiếng Việt."
)


class ChatRequest(BaseModel):
    message: str
    history: list[dict] = []   # [{"role": "user"|"assistant", "content": "..."}]


class ChatResponse(BaseModel):
    reply: str


@app.post("/api/chat", response_model=ChatResponse)
def chat(req: ChatRequest):
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages += req.history[-10:]   # Giữ tối đa 10 lượt gần nhất
    messages.append({"role": "user", "content": req.message})

    try:
        response = client.chat.completions.create(
            model="openai/gpt-oss-120b",
            messages=messages,
            temperature=0.7,
            max_tokens=1024,
            stream=False,
        )
        reply = response.choices[0].message.content
    except Exception as e:
        reply = f"Xin lỗi, tôi đang gặp sự cố kỹ thuật. Vui lòng thử lại sau. ({e})"

    return ChatResponse(reply=reply)
