# Собирается из корня day17: docker build -f user-service/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :user-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/user-service/build/libs/user-service-0.0.1-SNAPSHOT.jar app.jar

# Файл SQLite живёт на томе: без него база исчезнет вместе с контейнером.
ENV PORT=8097 DB_PATH=/data/users.db
RUN useradd --system --uid 1001 app && mkdir -p /data && chown app /data
VOLUME /data
USER app

EXPOSE 8097
ENTRYPOINT ["java", "-jar", "app.jar"]
