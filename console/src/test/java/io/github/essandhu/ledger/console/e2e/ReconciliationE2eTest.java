package io.github.essandhu.ledger.console.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import com.jayway.jsonpath.JsonPath;
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
 * <p>Since M8-stretch it runs against the CONTAINERIZED console, which makes it the one place
 * the OIDC URL split is proven from both ends at once: a login that completes means the
 * browser reached Keycloak at {@code localhost:8081} <em>and</em> the console reached it at
 * {@code keycloak:8080} for the token exchange and the JWKS fetch.
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
    /** Browser-facing Keycloak — the same URL the console redirects to and the admin API lives on. */
    private static final String KEYCLOAK = System.getProperty(
            "ledger.e2e.keycloak-base-url", "http://localhost:8081");
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
                fail(("the console at %s is not UP (%d) — check `docker compose "
                        + "--profile console logs console`")
                        .formatted(BASE, health.statusCode()));
            }
        } catch (IOException | InterruptedException unreachable) {
            fail(("no console at %s — the console is a compose service (M8-stretch): "
                    + "`docker compose --profile console up -d --build --wait`. A plain "
                    + "`up` without the profile starts everything EXCEPT the console.")
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

            // The drift badge (M8-stretch) reports the sweep that just ran, from the page
            // chrome — polled in by htmx after the page rendered, which is the whole design:
            // no page pays for a reconciliation read it didn't ask for. Playwright retries the
            // assertion until the first poll lands, so this needs no sleep.
            assertThat(page.locator(".drift-badge-slot .status-badge")).hasText("DRIFT");
            assertThat(page.locator(".drift-badge-slot .drift-count")).not().hasText("0");

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
    @DisplayName("signing out ends the Keycloak session too — RP-initiated logout, console → Keycloak")
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

    /**
     * The other direction (M8-stretch): an administrator revokes the session in Keycloak, and
     * the console session dies with it. Until this milestone that could not work at all —
     * back-channel logout requires Keycloak to reach a console URL, and a container cannot
     * dial a process on the developer's host. ADR-0007 recorded the one-way logout as an
     * accepted trade-off "deferred to the containerized stretch"; containerizing supplied the
     * URL, so this is the cell that closes it.
     *
     * <p>Deliberately driven through Keycloak's ADMIN API rather than by clicking anything:
     * the whole point is that the console is told about a logout it did not participate in
     * and cannot observe any other way. Nothing here touches the browser until the very last
     * step, which is the only honest way to ask "did the session actually die?".
     */
    @Test
    @DisplayName("a Keycloak-side revocation ends the console session — back-channel logout, Keycloak → console")
    void keycloak_side_revocation_ends_the_console_session(TestInfo test) {
        try (BrowserContext context = browser.newContext()) {
            Page page = signIn(context, "ops", "ops");
            // Control: the session is live and rendering before anything is revoked, so a
            // failure below cannot be "it was never logged in".
            assertThat(page.locator("#runs-heading")).hasText("Reconciliation runs");

            revokeKeycloakSessionsFor("ops");

            // Keycloak POSTs the logout token to the console while serving the call above, so
            // this is normally true on the first navigation — but it is a cross-container hop,
            // and a bounded retry beats a race that fails once a fortnight in CI. The failure
            // message names the cause rather than leaving a Playwright timeout to be read.
            for (int attempt = 0; attempt < 20; attempt++) {
                page.navigate(BASE + "/reconciliation");
                if (page.url().contains("/protocol/openid-connect/auth")) {
                    assertThat(page.locator("#kc-form-login")).isVisible();
                    screenshot(page, test);
                    return;
                }
                page.waitForTimeout(500);
            }
            fail("the console session survived a Keycloak-side revocation — back-channel "
                    + "logout did not arrive (is the console reachable from Keycloak at the "
                    + "realm's backchannel.logout.url, i.e. running as a compose service?)");
        }
    }

    /**
     * Ends every SSO session {@code username} has, the way an operator would: Keycloak's admin
     * REST API. The realm's bootstrap admin is the compose stack's {@code admin/admin}, and
     * {@code admin-cli} is Keycloak's own built-in public client for exactly this.
     */
    private static void revokeKeycloakSessionsFor(String username) {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            String token = JsonPath.read(post(http,
                    KEYCLOAK + "/realms/master/protocol/openid-connect/token",
                    "grant_type=password&client_id=admin-cli&username=admin&password=admin",
                    null), "$.access_token");
            // exact=true: a prefix match would find the wrong user the moment the realm gains
            // one whose name starts with this one's.
            String users = get(http,
                    KEYCLOAK + "/admin/realms/ledger/users?exact=true&username=" + username,
                    token);
            String userId = JsonPath.read(users, "$[0].id");
            post(http, KEYCLOAK + "/admin/realms/ledger/users/" + userId + "/logout", "", token);
        } catch (IOException | InterruptedException unreachable) {
            fail("could not reach Keycloak's admin API at " + KEYCLOAK, unreachable);
        }
    }

    private static String post(HttpClient http, String uri, String form, String bearer)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(http, request.build());
    }

    private static String get(HttpClient http, String uri, String bearer)
            throws IOException, InterruptedException {
        return send(http, HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + bearer)
                .GET().build());
    }

    private static String send(HttpClient http, HttpRequest request)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            fail("Keycloak admin API %s answered %d: %s"
                    .formatted(request.uri(), response.statusCode(), response.body()));
        }
        return response.body();
    }
}
