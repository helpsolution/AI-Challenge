# Собирается из корня day17: docker build -f user-service-mcp/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :user-service-mcp:jar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/user-service-mcp/build/libs/user-service-mcp-0.0.1-SNAPSHOT.jar app.jar

ENV PORT=8098
USER 1001:1001

EXPOSE 8098
ENTRYPOINT ["java", "-jar", "app.jar"]
