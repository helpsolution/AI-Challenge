# Собирается из корня day18: docker build -f agent/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :agent:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/agent/build/install/agent /app

# Ключ DeepSeek и адрес MCP приходят переменными окружения: .env в образ не копируется (.dockerignore).
USER 1001:1001
ENTRYPOINT ["/app/bin/agent"]
