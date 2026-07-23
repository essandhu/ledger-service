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
    testImplementation("org.awaitility:awaitility")
    testImplementation(libs.archunit.core)
}

tasks.test {
    useJUnitPlatform()
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
                // Coverage ratchet (TEST-STRATEGY.md §5): M1 → 0.70, M2 → 0.80 (current), M4 → 0.85, M7 → 0.90.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
