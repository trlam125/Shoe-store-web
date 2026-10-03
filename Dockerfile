# syntax=docker/dockerfile:1

# ----------------------------------------------------------
# Stage 1: build Spring Boot
# ----------------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-21 AS java-build
WORKDIR /build

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# ----------------------------------------------------------
# Stage 2: one Northflank runtime for Spring Boot + FastAPI
# ----------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8081 \
    AI_SERVICE_AUTOSTART=false \
    AI_SERVICE_SETUP_VENV=false \
    AI_SERVICE_URL=http://127.0.0.1:8001 \
    AI_HOST=127.0.0.1 \
    AI_PORT=8001 \
    AI_RELOAD=false \
    AI_STORE_BASE_URL=http://127.0.0.1:8081 \
    AI_TRUSTED_IMAGE_ORIGINS=http://127.0.0.1:8081 \
    PRODUCT_IMAGE_STORAGE=database \
    TORCH_HOME=/opt/torch \
    OMP_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        libgomp1 \
        python3 \
        python3-pip \
        python3-venv \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install the AI runtime once during the image build. Keep CPU-only PyTorch to
# reduce image/runtime requirements on Northflank's non-GPU compute.
COPY ai-service/requirements.txt /tmp/ai-requirements.txt
RUN python3 -m venv /opt/ai-venv \
    && /opt/ai-venv/bin/python -m pip install --no-cache-dir --upgrade pip \
    && grep -Ev '^(torch|torchvision)==' /tmp/ai-requirements.txt > /tmp/ai-requirements-core.txt \
    && /opt/ai-venv/bin/python -m pip install --no-cache-dir -r /tmp/ai-requirements-core.txt \
    && /opt/ai-venv/bin/python -m pip install --no-cache-dir \
        torch==2.6.0 torchvision==0.21.0 \
        --index-url https://download.pytorch.org/whl/cpu \
    && rm -f /tmp/ai-requirements.txt /tmp/ai-requirements-core.txt

# Cache ResNet18 weights at build time so the first image-search request does
# not depend on a model download at runtime.
RUN mkdir -p /opt/torch \
    && /opt/ai-venv/bin/python -c "from torchvision.models import ResNet18_Weights, resnet18; resnet18(weights=ResNet18_Weights.DEFAULT)"

COPY --from=java-build /build/target/*.jar /app/app.jar
COPY ai-service /app/ai-service
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN groupadd --system lshoe \
    && useradd --system --gid lshoe --home-dir /app --shell /usr/sbin/nologin lshoe \
    && chmod 0755 /app/docker-entrypoint.sh \
    && chown -R lshoe:lshoe /app /opt/torch /opt/ai-venv

USER lshoe

# Only Spring Boot is public. FastAPI binds to 127.0.0.1:8001 inside the same
# container and cannot be reached directly from the Internet.
EXPOSE 8081/tcp

ENTRYPOINT ["/app/docker-entrypoint.sh"]
