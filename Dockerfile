# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
ENV DEBIAN_FRONTEND=noninteractive \
    SPRING_PROFILES_ACTIVE=prod \
    AI_SERVICE_AUTOSTART=false \
    AI_SERVICE_SETUP_VENV=false \
    AI_SERVICE_URL=http://127.0.0.1:8001 \
    AI_HOST=127.0.0.1 \
    AI_PORT=8001 \
    AI_RELOAD=false \
    PRODUCT_IMAGE_STORAGE=database \
    MANAGED_PROXY_RUNTIME=true \
    TORCH_HOME=/opt/torch \
    OMP_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1 \
    MALLOC_ARENA_MAX=2 \
    JAVA_TOOL_OPTIONS="-Xms32m -Xmx160m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -Xss256k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates libgomp1 python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY ai-service/requirements.txt /tmp/req.txt
RUN python3 -m venv /opt/ai-venv \
    && /opt/ai-venv/bin/pip install --no-cache-dir --upgrade pip \
    && grep -Ev '^(torch|torchvision)==' /tmp/req.txt > /tmp/core.txt \
    && /opt/ai-venv/bin/pip install --no-cache-dir -r /tmp/core.txt \
    && /opt/ai-venv/bin/pip install --no-cache-dir torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cpu \
    && rm -f /tmp/req.txt /tmp/core.txt
RUN mkdir -p /opt/torch && /opt/ai-venv/bin/python -c "from torchvision.models import ResNet18_Weights,resnet18;resnet18(weights=ResNet18_Weights.DEFAULT)"
COPY --from=build /build/target/*.jar /app/app.jar
COPY ai-service /app/ai-service
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN groupadd -r lshoe && useradd -r -g lshoe -d /app -s /usr/sbin/nologin lshoe \
    && chmod 0755 /app/docker-entrypoint.sh \
    && chown -R lshoe:lshoe /app /opt/torch /opt/ai-venv
USER lshoe
EXPOSE 8081
ENTRYPOINT ["/app/docker-entrypoint.sh"]
