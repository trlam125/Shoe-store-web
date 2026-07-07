import os
import re
import base64
from dotenv import load_dotenv
from openai import OpenAI
import streamlit as st

load_dotenv()

client = OpenAI(
    base_url="https://integrate.api.nvidia.com/v1",
    api_key=os.getenv("NVIDIA_API_KEY")
)

st.set_page_config(page_title="AI Chatbot", page_icon="🤖", layout="wide")
st.title("🤖 Có gì khó thì bỏ qua, vì tôi là chatbot nổi loạn")


def fix_latex(text: str) -> str:
    """
    Chuyển công thức LaTeX dạng \\(...\\), \\[...\\]
    sang dạng Markdown mà Streamlit hiển thị tốt hơn.
    """
    if not isinstance(text, str):
        return text

    text = re.sub(r"\\\((.*?)\\\)", r"$\1$", text, flags=re.S)
    text = re.sub(r"\\\[(.*?)\\\]", r"$$\1$$", text, flags=re.S)

    return text


st.markdown("""
<style>
.thinking {
    font-size: 18px;
    font-weight: 500;
    display: inline-flex;
    align-items: center;
    gap: 2px;
}

.dot {
    display: inline-block;
    animation: sineWave 1.2s infinite ease-in-out;
    font-size: 28px;
    line-height: 1;
}

.dot:nth-child(2) {
    animation-delay: 0.2s;
}

.dot:nth-child(3) {
    animation-delay: 0.4s;
}

@keyframes sineWave {
    0%, 100% {
        transform: translateY(0);
    }
    50% {
        transform: translateY(-8px);
    }
}
</style>
""", unsafe_allow_html=True)


if "messages" not in st.session_state:
    st.session_state.messages = [
        {
            "role": "system",
            "content": (
                "Bạn là chatbot AI thân thiện, trả lời bằng tiếng Việt. "
                "Khi viết công thức toán học, hãy dùng cú pháp Markdown LaTeX "
                "với $...$ cho công thức trong dòng và $$...$$ cho công thức riêng dòng. "
                "Không dùng \\(...\\) hoặc \\[...\\]."
            )
        }
    ]


uploaded_files = st.file_uploader(
    "Upload ảnh hoặc file",
    type=["png", "jpg", "jpeg", "txt", "md", "py", "csv", "json"],
    accept_multiple_files=True
)

file_text = ""
image_parts = []

if uploaded_files:
    for file in uploaded_files:
        if file.type.startswith("image/"):
            image_bytes = file.read()
            image_base64 = base64.b64encode(image_bytes).decode("utf-8")

            image_parts.append({
                "type": "image_url",
                "image_url": {
                    "url": f"data:{file.type};base64,{image_base64}"
                }
            })

            st.image(image_bytes, caption=file.name, width=300)

        else:
            try:
                content = file.read().decode("utf-8")
                file_text += f"\n\n--- Nội dung file: {file.name} ---\n{content}"
                st.success(f"Đã đọc file: {file.name}")
            except Exception:
                st.warning(f"Không đọc được file: {file.name}")


for message in st.session_state.messages:
    if message["role"] == "system":
        continue

    with st.chat_message(message["role"]):
        if isinstance(message["content"], str):
            st.markdown(fix_latex(message["content"]))

        elif isinstance(message["content"], list):
            for part in message["content"]:
                if part.get("type") == "text":
                    st.markdown(fix_latex(part.get("text", "")))


prompt = st.chat_input("Nhập tin nhắn...")

if prompt:
    user_text = prompt

    if file_text:
        user_text += f"\n\nDưới đây là nội dung file người dùng upload:\n{file_text}"

    if image_parts:
        user_content = [{"type": "text", "text": user_text}] + image_parts
    else:
        user_content = user_text

    st.session_state.messages.append({
        "role": "user",
        "content": user_content
    })

    with st.chat_message("user"):
        st.markdown(fix_latex(prompt))

    with st.chat_message("assistant"):
        thinking_placeholder = st.empty()

        thinking_placeholder.markdown("""
        <div class="thinking">
            <span>Đang suy nghĩ</span>
            <span class="dot">.</span>
            <span class="dot">.</span>
            <span class="dot">.</span>
        </div>
        """, unsafe_allow_html=True)

        try:
            response = client.chat.completions.create(
                model="openai/gpt-oss-120b",
                messages=st.session_state.messages,
                temperature=1,
                top_p=1,
                max_tokens=4096,
                stream=False
            )

            thinking_placeholder.empty()

            answer = response.choices[0].message.content
            answer = fix_latex(answer)

            st.markdown(answer)

            st.session_state.messages.append({
                "role": "assistant",
                "content": answer
            })

        except Exception as e:
            thinking_placeholder.empty()
            st.error(f"Lỗi: {e}")