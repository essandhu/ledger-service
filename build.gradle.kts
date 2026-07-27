import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.pitest)
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

    // PIT's tool classpath (separate from testImplementation). No released pitest-junit5-plugin
    // supports JUnit Platform 6 (pitest-junit5-plugin#113 — the failure mode is a SILENT
    // "0 tests per mutation" green run); forcing the Platform 6 launcher artifacts the test
    // runtime actually uses onto PIT's own classpath is the workaround from the (unmerged)
    // fix PR #114. Versions ride the Boot BOM so the 4.1.1 re-pin cannot desynchronize them.
    pitest(platform(SpringBootPlugin.BOM_COORDINATES))
    pitest("org.junit.platform:junit-platform-launcher")
    pitest("org.junit.jupiter:junit-jupiter-api")
    pitest("org.junit.jupiter:junit-jupiter-engine")
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
                // Coverage ratchet (TEST-STRATEGY.md §5): M1 → 0.70, M2 → 0.80, M4 → 0.85, M7 → 0.90 (final).
                minimum = "0.90".toBigDecimal()
            }
        }
        // M7 (TEST-STRATEGY.md §5): the domain packages carry the invariants — every line is
        // either exercised or should not exist. 1.00 with NO exclusions: the only uncovered
        // code in the codebase (bootstrap main(), the JRE-mandated SHA-256 catch — four lines)
        // lives outside domain, so this rule stays exact where exactness is the point.
        rule {
            element = "PACKAGE"
            includes = listOf(
                    "io.github.essandhu.ledger.domain.model",
                    "io.github.essandhu.ledger.domain.error")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

pitest {
    // TEST-STRATEGY §5 (M7): mutation testing is a NON-GATING report — no mutationThreshold,
    // deliberately NOT wired into `check`; run it with `./gradlew pitest` (report lands in
    // build/reports/pitest). The Platform-6 classpath workaround lives in the `pitest`
    // dependency configuration above; the known upstream failure mode is a SILENT green run
    // with "0 tests per mutation", so after any toolchain bump verify mutations.xml still
    // contains KILLED mutations before trusting the numbers.
    pitestVersion = libs.versions.pitest.core
    junit5PluginVersion = libs.versions.pitest.junit5
    // Mutate only the framework-free core: the domain invariants and use-case services are
    // where a surviving mutant means a missing proof. Adapters are integration-tested against
    // real infrastructure PIT must never fork (Testcontainers per mutant).
    targetClasses = setOf(
            "io.github.essandhu.ledger.domain.*",
            "io.github.essandhu.ledger.application.*")
    // targetTests DEFAULTS to targetClasses' globs — without this line every test outside
    // domain/application would be silently dropped from the covering-test set.
    targetTests = setOf("io.github.essandhu.ledger.*")
    // Fast deterministic unit suites only, twice over (name globs + tag groups): one
    // Testcontainers suite in the covering set would fork real PostgreSQL per mutant.
    excludedTestClasses = setOf(
            "*IntegrationTest",
            "io.github.essandhu.ledger.concurrency.*",
            "io.github.essandhu.ledger.WalkingSkeletonTest",
            "io.github.essandhu.ledger.OpenApiDocumentationTest")
    excludedGroups = setOf("integration", "concurrency")
    threads = 4
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
}
