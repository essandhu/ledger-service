import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
}

group = "io.github.essandhu"
version = "0.1.0-SNAPSHOT"
description = "Standalone double-entry ledger service — see docs/PLAN.md"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Boot 4 documented native-BOM path (no io.spring.dependency-management plugin).
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    // Boot 4 starter names (spring-boot-starter-web / -oauth2-resource-server are deprecated aliases).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.springdoc.openapi.webmvc.ui)
    implementation(libs.java.uuid.generator)
    runtimeOnly(libs.postgresql.jdbc)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    // M6: Batch's test factory for metadata instances (the listener unit tests build
    // JobExecutions without a JobRepository). BOM-managed like the rest of spring-batch-*.
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.awaitility:awaitility")
    testImplementation(libs.archunit.core)
}

tasks.test {
    useJUnitPlatform {
        // M5 (TEST-STRATEGY §2): the concurrency suite is the first slow suite to need
        // isolation, so it leaves the default lane for its own tag-filtered task below.
        excludeTags("concurrency")
    }
    // The OpenAPI CI artifact is written by OpenApiDocumentationTest to this Gradle-owned path,
    // so the test makes no working-directory assumption (and IDE runs behave identically).
    systemProperty("ledger.openapi.output",
        layout.buildDirectory.file("openapi/openapi.json").get().asFile.absolutePath)
    // Declared as a task output so build-cache hits restore it — otherwise a FROM-CACHE test
    // task on an unchanged-input CI run would upload nothing and fail the openapi artifact step.
    outputs.file(layout.buildDirectory.file("openapi/openapi.json"))
    // Property-harness replay contract (ADR-0005, TEST-STRATEGY §2.2): `Property` reads these at
    // check time inside the forked test JVM, so the CLI promises `-Dledger.property.seed=<long>`
    // (replay a falsified run) and `-Dledger.property.iterations=<n>` (raise the budget) only work
    // if Gradle forwards them. Forwarded only when set, so the task's inputs — and its cacheability —
    // are unchanged for ordinary runs.
    listOf("ledger.property.seed", "ledger.property.iterations").forEach { key ->
        providers.systemProperty(key).orNull?.let { value -> systemProperty(key, value) }
    }
    finalizedBy(tasks.jacocoTestReport)
}

// M5 (TEST-STRATEGY §2, §4): the parallel-writer proof lane — I6 racy, I7, I8 racy, I17 —
// tag-filtered out of `test` so the fast deterministic lane stays fast, wired into `check` so
// a plain `./gradlew build` still proves every invariant (per-milestone definition of done).
// CI runs it as its own required job in parallel with the default lane (`build -x concurrencyTest`).
val concurrencyTest by tasks.registering(Test::class) {
    description = "Runs the M5 concurrency-proof suite (tag: concurrency)."
    group = "verification"
    useJUnitPlatform {
        includeTags("concurrency")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // Sizing knobs (TEST-STRATEGY §4: "thread count and iteration count are properties so a
    // nightly run can crank them") — forwarded into the forked JVM exactly like the property
    // harness's seed/iterations knobs on `test`, and only when set. The property knobs are
    // forwarded too: the suite may seed randomized workloads.
    listOf("ledger.concurrency.threads", "ledger.concurrency.iterations",
        "ledger.property.seed", "ledger.property.iterations").forEach { key ->
        providers.systemProperty(key).orNull?.let { value -> systemProperty(key, value) }
    }
    // A required proof must actually RUN: every green is a fresh interleaving sample, never a
    // restored one. Without these, org.gradle.caching=true + the CI cache action could answer
    // the concurrency job FROM-CACHE on an unchanged-input run — a vacuous green for a lane
    // whose entire value is nondeterministic re-sampling.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("the stress lane must re-sample interleavings on every run") { true }
    // mustRunAfter, not shouldRunAfter: with org.gradle.parallel + the configuration cache,
    // shouldRunAfter is dropped under parallel scheduling — the two lanes would fork two JVMs
    // and two PostgreSQL containers SIMULTANEOUSLY on a local `build`, and the stress bounds
    // (an overrun IS an I17 failure) would compete with the whole default lane for CPU.
    mustRunAfter(tasks.test)
    // The stress lane runs with the JaCoCo agent OFF: its coverage is unconsumed by design
    // (the gate decision below), so instrumentation would only eat into the bounded hammers.
    configure<JacocoTaskExtension> {
        isEnabled = false
    }
}

tasks.check {
    // The coverage gate below deliberately stays fed by the default `test` lane alone: the
    // ratchet measures what the fast deterministic suites prove; the stress lane exists to
    // break interleavings, not to pad line coverage.
    dependsOn(concurrencyTest)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Coverage ratchet (TEST-STRATEGY.md §5): M1 → 0.70, M2 → 0.80, M4 → 0.85 (current), M7 → 0.90.
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
