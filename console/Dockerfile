# The read-only console (M8-stretch, ADR-0007). Built from the REPO ROOT as context — Gradle
# has to evaluate the settings script and the root project before it can build the subproject —
# so compose points at it with `dockerfile: console/Dockerfile` and `context: .`.
#
# Deliberately a SECOND image rather than a second stage in the core's Dockerfile: these are
# two applications with two lifecycles, and the console's default deployment is "not deployed
# at all" (compose profile `console`). The cost is a second Gradle dependency resolution at
# build time; the alternative is coupling the core image's cache to console changes.
FROM gradle:9.6.1-jdk21 AS build
WORKDIR /workspace
# Root scripts first: the settings script, the root build script (evaluated for every build),
# the version catalog and wrapper properties. Separate from the sources below so a source
# change re-runs the compile and not the dependency resolution.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY console/build.gradle.kts ./console/build.gradle.kts
COPY console/src ./console/src
# The `java` plugin leaves a `-plain` jar beside the boot jar, so a bare console-*.jar glob
# resolves to TWO paths and the copy fails on the pair — the same trap the CI lane documents.
# The version is not hardcoded here: it lives in the build script and would rot silently.
RUN gradle :console:bootJar --no-daemon \
    && cp "$(find console/build/libs -name 'console-*.jar' -not -name '*-plain.jar')" application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Runtime stage, mirroring the core image: four COPY steps in Boot's layer order (least → most
# volatile), so an application-only change re-pulls kilobytes rather than the dependency tree.
FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system ledger && useradd --system --gid ledger ledger
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# CDS training run, as in the core image (runs as root so the .jsa lands in the root-owned
# /app; the runtime user only reads it). The core needs a whole `cds-training` profile to keep
# Flyway, Hibernate and Batch off the wire during its full context refresh — the console needs
# NOTHING, and that is a direct consequence of this milestone: with the OIDC registration
# hand-built instead of discovered (ConsoleOidcConfig), the console's context refreshes with
# no network at all. Before M8-stretch this step could not have existed.
RUN java -XX:ArchiveClassesAtExit=application.jsa \
    -Dspring.context.exit=onRefresh \
    -jar application.jar

USER ledger
EXPOSE 8090
ENTRYPOINT ["java", "-XX:SharedArchiveFile=application.jsa", "-jar", "application.jar"]
