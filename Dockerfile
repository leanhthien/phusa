# syntax=docker/dockerfile:1

# --- build stage: compile the bootJar with JDK 21 ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the build definition first so the dependency layer caches independently of
# source changes.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# --- runtime stage: JRE only, non-root ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd -r -u 1001 phusa
# bootJar only (the -plain.jar is not runnable and doesn't match this glob).
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
