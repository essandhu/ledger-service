import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
}

group = "io.github.essandhu"
version = "0.1.0-SNAPSHOT"
description = "Read-only web console for the ledger — a separate OAuth2-client Boot app (ADR-0007)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Boot 4 documented native-BOM path, same as the root project.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    // Boot 4 starter names (spring-boot-starter-oauth2-client is the deprecated alias).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // There is NO "-springsecurity7" artifact: the 6-line is what the Boot 4.1 BOM manages
    // (3.1.5.RELEASE) and it carries Security 7 support — Central's search index is stale on
    // this; trust the BOM (ADR-0007).
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.test {
    useJUnitPlatform()
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
                // The console's own ratchet starts where the core's did at M1: 0.70, zero
                // exclusions. Deliberately NOT the root 0.90/domain-1.00 rules — JaCoCo config
                // is per-project and aggregation is opt-in only, so neither project's gate can
                // leak into the other (ADR-0007).
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
