package io.github.essandhu.ledger.console.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import io.github.essandhu.ledger.console.support.ConsoleE2eTest;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The whole console story in a real browser (M8c): the Keycloak authorization-code login, the
 * token relay carrying the user's own access token to the ledger, the ID-token roles mapper
 * deciding what renders, CSRF on a state-changing POST, and htmx's confirm — none of which the
 * MockMvc suite can prove, because each one is the piece it stubs.
 *
 * <p>Its target is ADR-0007's demo promise: <em>trigger a sweep from the browser and watch the
 * drift finding appear with delta 7</em>. The drift is seeded out of band first
 * ({@code scripts/e2e-fixture.sh} — superuser SQL, the only way drift can exist per ADR-0002);
 * this suite starts the sweep that convicts it, from a button, as a person would.
 *
 * <p>Preconditions are checked, not assumed: the health probe below fails with the command to
 * run rather than letting Playwright time out against a dead port. One
 * {@link BrowserContext} per cell — a shared context would carry the {@code ops} session into
 * the {@code viewer} cell and quietly prove nothing.
 */
@ConsoleE2eTest
@DisplayName("Console E2E (M8c): real Keycloak login, ops-only trigger, drift finding with delta 7")
class ReconciliationE2eTest {

    private static final String BASE = System.getProperty(
            "ledger.e2e.console-base-url", "http://localhost:8090");
    private static final Path SCREENSHOTS = Path.of(System.getProperty(
            "ledger.e2e.screenshots", "build/reports/playwright"));

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launchBrowser() {
        requireConsoleUp();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(!Boolean.getBoolean("ledger.e2e.headed")));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /**
     * Fails with the fix rather than a 30-second Playwright timeout. Anonymous by design: the
     * console's health endpoint is one of exactly two unauthenticated surfaces (ADR-0007).
     */
    private static void requireConsoleUp() {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create(BASE + "/actuator/health"))
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (health.statusCode() != 200 || !health.body().contains("\"status\":\"UP\"")) {
                // Parenthesised: method invocation binds tighter than +, so `.formatted` on a
                // bare concatenation applies to the LAST literal only — and since Formatter
                // ignores surplus arguments, the %s and %d would print verbatim in the one
                // message whose whole job is to explain the missing precondition.
                fail(("the console at %s is not UP (%d) — start it with "
                        + "`./gradlew :console:bootJar && "
                        + "java -jar console/build/libs/console-*.jar`")
                        .formatted(BASE, health.statusCode()));
            }
        } catch (IOException | InterruptedException unreachable) {
            fail(("no console at %s — `docker compose up -d --wait`, then run the console on 8090 "
                    + "(it needs Keycloak alive first: eager issuer discovery, ADR-0007)")
                    .formatted(BASE), unreachable);
        }
    }

    /**
     * Signs in through the REAL Keycloak login form. Stock-theme selectors, re-verified against
     * the running Keycloak 26.7.0 container at write time (2026-07-27). {@code localhost}, never
     * {@code 127.0.0.1}: the realm's redirect-URI list is exact-match, and the two are different
     * strings to Keycloak even though they are the same host to the OS.
     */
    private static Page signIn(BrowserContext context, String user, String password) {
        Page page = context.newPage();
        page.navigate(BASE + "/reconciliation");
        // The bounce to Keycloak IS the first assertion: an unauthenticated console page must
        // never render.
        page.waitForURL("**/realms/ledger/protocol/openid-connect/auth**");
        page.fill("#username", user);
        page.fill("#password", password);
        page.click("#kc-login");
        page.waitForURL(BASE + "/reconciliation**");
        return page;
    }

    private static void screenshot(Page page, TestInfo test) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SCREENSHOTS.resolve(test.getTestMethod().orElseThrow().getName() + ".png"))
                .setFullPage(true));
    }

    @Test
    @DisplayName("ops triggers a sweep from the browser: DRIFT, delta 7, and the run tops the history")
    void ops_triggers_a_sweep_and_sees_the_drift(TestInfo test) {
        try (BrowserContext context = browser.newContext()) {
            Page page = signIn(context, "ops", "ops");
            // The relay worked: this table is the ledger's own answer, fetched with the token
            // Keycloak just issued to this browser session.
            assertThat(page.locator("#runs-heading")).hasText("Reconciliation runs");
            assertThat(page.locator(".whoami-name")).hasText("ops");

            // htmx's hx-confirm is a window.confirm — accept it, as a person clicking OK would.
            page.onDialog(dialog -> dialog.accept());
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Run reconciliation now")).click();

            // HX-Redirect lands the browser on the run the sweep created.
            page.waitForURL(BASE + "/reconciliation/runs/**");
            assertThat(page.locator(".identity-body .status-badge")).hasText("DRIFT");
            // The fixture's out-of-band +7, found by a sweep this browser started (I15).
            // first(): the fixture is deliberately re-runnable, so a database it has seeded
            // more than once carries one drifted account per run — each with the same +7, and
            // a bare locator over several rows is a Playwright strict-mode failure.
            assertThat(page.locator("td.delta").first()).hasText("7");
            // Drifted-accounts count, the second of the five — a DRIFT verdict must have one.
            assertThat(page.locator(".run-counts dd").nth(1)).not().hasText("0");
            screenshot(page, test);

            // Newest first, proven rather than pattern-matched: the run just created is the id
            // in the address bar, and it must be the row the history opens with.
            String runId = page.url().substring(page.url().lastIndexOf('/') + 1);
            // By accessible name, not by href: the topbar carries the same href, and a CSS
            // selector matching two elements is a Playwright strict-mode failure.
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("← All runs")).click();
            page.waitForURL(BASE + "/reconciliation");
            assertThat(page.locator("tbody tr").first().locator("a").first())
                    .hasAttribute("href", "/reconciliation/runs/" + runId);
            assertThat(page.locator("tbody tr").first().locator(".status-badge"))
                    .hasText("DRIFT");
        }
    }

    @Test
    @DisplayName("a viewer sees no trigger button — role-aware rendering through the real ID-token roles mapper")
    void viewer_sees_no_trigger(TestInfo test) {
        try (BrowserContext context = browser.newContext()) {
            Page page = signIn(context, "viewer", "viewer");
            assertThat(page.locator("#runs-heading")).hasText("Reconciliation runs");
            // The read surface is fully available; only the write control is absent. This is the
            // leg no MockMvc test reaches: the roles came from a Keycloak-issued ID token,
            // through the per-client protocol mapper, into ConsoleRealmRoleMapper.
            assertThat(page.locator("form.trigger-form")).hasCount(0);
            assertThat(page.locator("table.ruled")).isVisible();
            screenshot(page, test);
        }
    }

    @Test
    @DisplayName("signing out ends the Keycloak session too — RP-initiated logout, one way")
    void sign_out_returns_to_keycloak(TestInfo test) {
        try (BrowserContext context = browser.newContext()) {
            Page page = signIn(context, "ops", "ops");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Sign out")).click();
            // Back at the console root, which — with no session — bounces to Keycloak's login.
            page.waitForURL("**/realms/ledger/protocol/openid-connect/auth**");
            assertThat(page.locator("#kc-form-login")).isVisible();
            screenshot(page, test);
        }
    }
}
