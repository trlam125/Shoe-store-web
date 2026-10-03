# syntax=docker/dockerfile:1
FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TORCH_HOME=/opt/torch \
    OMP_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1 \
    MALLOC_ARENA_MAX=2 \
    AI_RELOAD=false
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libgomp1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir --upgrade pip \
    && grep -Ev '^(torch|torchvision)==' /tmp/requirements.txt > /tmp/core.txt \
    && pip install --no-cache-dir -r /tmp/core.txt \
    && pip install --no-cache-dir torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cpu \
    && rm -f /tmp/requirements.txt /tmp/core.txt
RUN mkdir -p /opt/torch && python -c "from torchvision.models import ResNet18_Weights,resnet18;resnet18(weights=ResNet18_Weights.DEFAULT)"
COPY app ./app
RUN useradd -r -d /app -s /usr/sbin/nologin lshoe && chown -R lshoe:lshoe /app /opt/torch
USER lshoe
EXPOSE 8001
CMD ["sh", "-c", "python -m uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8001} --workers 1"]
