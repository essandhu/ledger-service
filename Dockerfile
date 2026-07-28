# Build stage — Gradle version matches the wrapper/CI pin; also performs the jarmode
# extraction so the runtime stage starts from the CDS-friendly layered layout.
FROM gradle:9.6.1-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# Settings evaluation needs every included project's build script to exist (M8 made the build
# multi-project) — but only the ROOT bootJar is built here, hence the leading colon below.
# The console's SOURCES are deliberately not copied: they never ship in this image, and
# leaving them out of the layer keeps a console change from invalidating this build.
COPY console/build.gradle.kts ./console/build.gradle.kts
COPY src ./src
RUN gradle :bootJar --no-daemon \
    && cp build/libs/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Runtime stage. The four COPY steps mirror the Boot layer order (least → most volatile), so
# an application-only change re-pulls kilobytes, not the dependency tree.
FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system ledger && useradd --system --gid ledger ledger
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# CDS training run (runs as root so the .jsa lands in the root-owned /app; the runtime user
# only reads it). The archive is path-sensitive, hence trained here in the runtime layout.
# -Dspring.context.exit=onRefresh performs a FULL context refresh and no database exists at
# image-build time — the cds-training profile (application-cds-training.yaml +
# CdsTrainingBatchConfiguration) keeps Flyway, Hibernate, and Batch off the wire for this one
# run. The classes those paths would load are simply absent from the archive and load
# normally at runtime.
RUN java -XX:ArchiveClassesAtExit=application.jsa \
    -Dspring.context.exit=onRefresh \
    -Dspring.profiles.active=cds-training \
    -jar application.jar

USER ledger
EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=application.jsa", "-jar", "application.jar"]
