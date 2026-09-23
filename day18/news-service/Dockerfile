# Собирается из корня day18: docker build -f news-service/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :news-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/news-service/build/libs/news-service-0.0.1-SNAPSHOT.jar app.jar

# Файл SQLite живёт на томе: без него база исчезнет вместе с контейнером.
ENV PORT=8181 DB_PATH=/data/news.db
RUN useradd --system --uid 1001 app && mkdir -p /data && chown app /data
VOLUME /data
USER app

EXPOSE 8181
ENTRYPOINT ["java", "-jar", "app.jar"]
