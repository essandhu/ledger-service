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
    // M8b token relay: RestClientBuilderConfigurer, so the hand-built API client uses Boot's
    // auto-configured message converters (the app's Jackson settings, not a framework-default
    // mapper) AND so tests can bind a MockRestServiceServer through the same configurer path.
    // (Jackson 3 bundles java.time in databind, so Instants would parse either way — the
    // converters and the test seam are the real reasons.)
    implementation("org.springframework.boot:spring-boot-restclient")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    // MockServerRestClientCustomizer — binds the API client's builder to a MockRestServiceServer
    // through the SAME configurer path production uses, so bind-before-build ordering is moot.
    testImplementation("org.springframework.boot:spring-boot-restclient-test")
    testImplementation(libs.jsoup)
    // M8c e2e lane: the plain Playwright library, no JUnit-Platform coupling (ADR-0007) —
    // its @UsePlaywright extension is experimental and upstream-tested only on Jupiter 5.14.
    testImplementation(libs.playwright)
}

tasks.test {
    useJUnitPlatform {
        // The e2e lane is tag-filtered OUT of the default lane, and this exclusion is not
        // optional: an @Tag("e2e") class reached by `:console:test` would launch a browser at
        // a compose stack and a host console that the "Console build" job does not have.
        excludeTags("e2e")
    }
    finalizedBy(tasks.jacocoTestReport)
}

// M8c: the browser lane (ADR-0007). Deliberately NOT wired into `check` — the ONE place this
// diverges from the root project's concurrencyTest precedent. concurrencyTest can join `check`
// because Testcontainers gives it its own infrastructure; this lane needs an externally started
// compose stack (console included since M8-stretch), so wiring it in would break
// `./gradlew build` and the required "Console build" job. CI runs it as its own job.
tasks.register<Test>("e2eTest") {
    description = "Runs the console e2e suite in a real browser (tag: e2e). " +
        "Needs `docker compose --profile console up -d --build --wait` first."
    group = "verification"
    useJUnitPlatform {
        includeTags("e2e")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // Where the console is, and whether to watch it work — forwarded only when set, so an
    // ordinary run takes the defaults and the task's inputs (and cacheability) are unchanged.
    // Deliberately NO credential knobs: each cell is ABOUT its user's role (ops has the
    // trigger, viewer does not), so a configurable username would make the assertions
    // meaningless rather than flexible.
    listOf("ledger.e2e.console-base-url", "ledger.e2e.keycloak-base-url",
        "ledger.e2e.headed").forEach { key ->
        providers.systemProperty(key).orNull?.let { value -> systemProperty(key, value) }
    }
    // Gradle-owned absolute path: a working-directory-relative one would land wherever the
    // forked JVM happens to start, and CI uploads exactly this directory.
    systemProperty("ledger.e2e.screenshots",
        layout.buildDirectory.dir("reports/playwright").get().asFile.absolutePath)
    // Playwright's Java bindings otherwise run their own `install` on Playwright.create(),
    // which fetches EVERY browser — chromium, firefox and webkit — even though this lane drives
    // only chromium. installPlaywrightBrowsers is the one place that download happens, so a
    // missing binary is a loud launch failure pointing at a skipped step, not a silent 200 MB.
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    // Same reasoning as concurrencyTest: this lane's verdict depends on state Gradle cannot see
    // (a live stack), so an UP-TO-DATE or FROM-CACHE answer would be a vacuous green.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("the e2e lane's verdict depends on a live stack Gradle cannot see") { true }
    mustRunAfter(tasks.test)
    // No JaCoCo: the app under test is a SEPARATE JVM, so the agent here would record the
    // driver's own coverage — and a stray e2eTest.exec would be an unconsumed artifact next to
    // the 0.70 gate, which stays fed by `test` alone.
    configure<JacocoTaskExtension> {
        isEnabled = false
    }
}

// Downloads the chromium build Playwright's Java bindings expect, via Playwright's own CLI.
// Deliberately not cacheable and not up-to-date-checkable: it mutates a machine-global
// directory (~/.cache/ms-playwright) and shells out to the system package manager for
// chromium's shared libraries, neither of which Gradle can model. Playwright's own docs say
// not to cache the browser binaries, so CI does not either.
tasks.register<JavaExec>("installPlaywrightBrowsers") {
    description = "Installs the chromium build the e2e lane drives (Playwright's own CLI)."
    group = "verification"
    mainClass = "com.microsoft.playwright.CLI"
    classpath = sourceSets.test.get().runtimeClasspath
    args("install", "--with-deps", "chromium")
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
