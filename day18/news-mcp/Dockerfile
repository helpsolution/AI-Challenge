# Собирается из корня day18: docker build -f news-mcp/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :news-mcp:jar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/news-mcp/build/libs/news-mcp-0.0.1-SNAPSHOT.jar app.jar

ENV PORT=8182
USER 1001:1001

EXPOSE 8182
ENTRYPOINT ["java", "-jar", "app.jar"]
