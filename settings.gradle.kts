plugins {
    // Resolves JDK toolchains (Java 21) automatically on machines that lack them.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ledger"

// M8 (ADR-0007): the read-only console is a separate Boot app in its own subproject, so the
// core keeps its pure resource-server posture. Nothing is shared between the projects (no
// subprojects {} block, deliberately) — but unqualified task names now fan out to BOTH
// projects, so CI and the Dockerfile invoke root tasks with a leading colon (`:build`, `:bootJar`).
include("console")
