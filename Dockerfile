# Build stage — Gradle version matches the wrapper/CI pin.
FROM gradle:9.6.1-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle bootJar --no-daemon

# Runtime stage.
# TODO(M7): layered-jar extraction (jarmode tools) + CDS for faster startup and better image caching.
FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system ledger && useradd --system --gid ledger ledger
USER ledger
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
