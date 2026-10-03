# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
ENV SPRING_PROFILES_ACTIVE=prod \
    AI_SERVICE_AUTOSTART=false \
    AI_SERVICE_SETUP_VENV=false \
    PRODUCT_IMAGE_STORAGE=database \
    MANAGED_PROXY_RUNTIME=true \
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4 \
    SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1 \
    JAVA_TOOL_OPTIONS="-Xms32m -Xmx280m -XX:MaxMetaspaceSize=112m -XX:ReservedCodeCacheSize=32m -Xss384k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
COPY --from=build /build/target/*.jar app.jar
RUN groupadd -r lshoe && useradd -r -g lshoe -d /app -s /usr/sbin/nologin lshoe \
    && chown -R lshoe:lshoe /app
USER lshoe
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
