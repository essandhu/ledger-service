# ADR-0007: Read-only console — a separate OAuth2-client app, not a SPA and not an embedded UI

- Status: Accepted
- Date: 2026-07-27 (M8)
- Deciders: project owner + M8 session

## Context and problem statement

v1 shipped a headless service: every guarantee in the
[guarantee table](../../README.md#the-guarantees) is proven by tests and demonstrated by curl.
The ledger's actual audience — ops and finance — works in a browser. M8 adds a **read-only web
console**: browse accounts, inspect entries, read balances and statements, and work the
reconciliation story. The one permitted action is triggering a sweep (`LEDGER_ADMIN`) —
additive-safe audit history, not money movement, so "read-only" stays honest where it matters.

The console is the API's first real consumer, and the UI is a window onto the invariants: the
entry view renders legs that visibly sum to zero, the drift finding shows snapshot vs computed
vs delta, and role-aware rendering makes the no-hierarchy model
([README §API](../../README.md#api)) something a person *experiences*.

Six sub-decisions, resolved together:

1. **Where does the console live?** (app topology)
2. **How does it render?**
3. **How does it call the API on the user's behalf?** (token relay)
4. **What issuer URL topology does login use?**
5. **What coverage gate applies to it?**
6. **Where does the console read the user's roles from?**

## Decision drivers

- The core's security posture must not change at all: it stays a pure OAuth2 resource server,
  and the no-hierarchy role model must survive into the UI untranslated.
- Every compatibility claim on this stack gets research-verified before adoption — the
  JUnit-Platform-6 casualty history ([ADR-0005](ADR-0005-property-testing-tooling.md)) is why
  nothing below is assumed. Verified against Boot 4.1.0 sources/BOM, Security 7.1 docs, Maven
  Central metadata, and Keycloak 26's own realm files.
- The console must be provable in a container-free test context: Boot's OAuth2 *client* mapper
  performs eager issuer discovery at startup whenever a provider `issuer-uri` is set, so a
  context built from production properties cannot load without a live Keycloak.
- One-developer portfolio economics: no second toolchain, no CORS surface, no API-versioning
  pressure invented by our own frontend.
- Demo ergonomics: clean clone → compose up → log in as `ops` in a browser.

## Considered options

For the app topology:

1. **Separate `console/` Gradle subproject — its own Boot app on :8090, an OAuth2
   *client* that calls the ledger API with the user's token — chosen.** The core keeps its
   posture; the console consumes the same API any external integrator would.
2. **SPA with a public PKCE client.** Rejected: the browser holds tokens, a CORS surface opens
   on the core, and a node toolchain enters the build — three new attack/maintenance surfaces
   to demonstrate a read-only page.
3. **UI inside the core app.** Rejected: the core would become client *and* resource server in
   one security config; the "pure resource server" claim — and every test pinned to it — dies.

For rendering:

1. **Thymeleaf server-side, htmx as a vendored static file with plain `hx-*` attributes —
   chosen.** Server-rendered pages keep the API the only data contract;
   `thymeleaf-extras-springsecurity6` is the artifact the Boot 4.1 BOM itself manages next to
   Security 7.1 (there is NO `-springsecurity7`; Central's search index is stale — trust the
   BOM). htmx arrives with the first dynamic fragment (M8b), committed under `static/`, not
   CDN-loaded.
2. **React (or any SPA framework).** Rejected with topology option 2 — same three surfaces.
3. **`htmx-spring-boot` integration library.** Rejected for now: built against Boot 4.0.x,
   unverified on 4.1; plain attributes need no library. Adopt later only if a smoke test earns
   it.

For the token relay:

1. **`OAuth2ClientHttpRequestInterceptor` on a hand-built `RestClient` — chosen** (lands with
   the first API call, M8b). Boot 4.1 does not auto-configure an OAuth2-aware
   `RestClient.Builder`, and — verified in Boot 4.1.0's autoconfiguration sources, correcting
   this ADR's own draft — **no `OAuth2AuthorizedClientManager` bean is auto-published in
   servlet apps** (only `ClientRegistrationRepository` + authorized-client repository/service).
   The console defines the ~6-line `DefaultOAuth2AuthorizedClientManager` bean itself.
2. **Manually forwarding the raw access token.** Rejected: loses the authorized-client
   machinery's silent refresh; hand-rolls what the interceptor already does correctly.
3. **WebClient.** Rejected: a reactive dependency for nothing.

For the issuer topology:

1. **Phase 1: console runs on the HOST (`./gradlew :console:bootRun`), issuer
   `http://localhost:8081/realms/ledger` — chosen.** One URL that is simultaneously valid for
   the browser's redirects and the JVM's eager startup discovery, with full OIDC metadata
   including `end_session_endpoint` for RP-initiated logout.
2. **Containerize immediately.** Rejected for M8a-c: unlike the resource server (which splits
   `issuer-uri` from an in-network `jwk-set-uri`), the client mapper has NO issuer/endpoint
   split — an in-container `localhost:8081` is the container's own loopback and the app fails
   at startup. The containerized recipe (explicit endpoint URIs, hand-built registration
   carrying browser-facing `end_session_endpoint` metadata, forgoing the discovery-time
   iss-equality check) is recorded here as the M8-stretch path with its trade-offs.

For the coverage gate:

1. **Own JaCoCo config, gate starting at 0.70 (the M1 precedent), own ratchet path, zero
   exclusions — chosen.** JaCoCo config is per-project and aggregation is opt-in only, so the
   root 0.90/domain-1.00 rules provably stay root-scoped in both directions.
2. **Inherit 0.90 day one.** Rejected: blocks UI iteration to defend a number the console
   hasn't earned yet; the core's gate measures six milestones of accumulated proof.
3. **Feed console coverage into the root rule.** Rejected: silently weakens the core gate —
   easy UI lines would subsidize hard domain lines.

For the role source:

1. **Per-client Keycloak protocol mapper putting `realm_access.roles` into the ID token,
   mirrored by a console-side `GrantedAuthoritiesMapper` — chosen.** Keycloak only puts realm
   roles in the *access* token by default; the mapper is scoped to `ledger-console` (no other
   client's tokens change shape), and the console's `ConsoleRealmRoleMapper` applies the same
   defensive parsing and prefix-filter discipline as the core's `LedgerRealmRoleConverter`.
2. **Parse the access-token JWT client-side.** Rejected: re-implements resource-server
   validation inside an app that deliberately has none, on a token the client is supposed to
   treat as opaque.
3. **Rely on the stock `roles` client scope's userinfo behavior.** Rejected: stock mapper
   placement varies by scope defaults across Keycloak versions; an explicit per-client mapper
   is deterministic and self-documenting in the realm file.

## Decision outcome

`console/` is a second Boot app (port 8090) with its own security chain: OIDC login against
the new confidential `ledger-console` client (the realm's only authorization-code client),
CSRF **on** (browser sessions — the deliberate inverse of the core's stateless CSRF-off
posture), RP-initiated logout via `OidcClientInitiatedLogoutSuccessHandler`, and an anonymous
surface of exactly health + static assets. Realm roles reach the session as `ROLE_*`
authorities via the ID-token mapper pair; `CONSOLE_*` bundle roles pass the filter alongside
`LEDGER_*` so the whoami page can show a composite next to the grants it expands to — the
composite-roles convenience path the realm always promised, now used by the human users
`ops` (`CONSOLE_OPS` → READ+ADMIN+METRICS) and `viewer` (READ only). The API itself never
checks a `CONSOLE_*` role.

Accepted trade-offs, on record: the host-run phase means the demo story is "compose up, then
one Gradle command" rather than pure compose (the stretch removes this); eager issuer
discovery makes Keycloak a startup dependency of the console (it fails fast, loudly, by
design); logout is **one-way** — Keycloak-side revocation does not reach the console, whose
session lives out the servlet timeout (OIDC back-channel logout needs a console URL Keycloak
can reach, so it is deferred to the containerized stretch); realm-file additions never reach
an existing volume (`--import-realm` skips existing realms), so upgrading stacks need one
`docker compose down -v` — the README's upgrade note names the console symptom; and the dev
realm ships human passwords (`ops`/`viewer`) in the import file — dev credentials in the same
spirit as every `*-dev-secret` already there.

CI grows a "Console build" job (`./gradlew :console:build`) and the existing root jobs are
scoped with a leading colon in the same commit that adds `include("console")` — job *names*
are branch-protection keys and only ever added, never renamed. The core's Docker image build
copies only the console's build script (settings evaluation needs it) and builds `:bootJar`,
root-scoped.

## Erratum (2026-07-27, M8b — factual correction, decision unchanged)

The token-relay option 1 wording "no `OAuth2AuthorizedClientManager` bean is auto-published
in servlet apps" is true of **Boot's** autoconfiguration but incomplete: Spring **Security**
7.1's `OAuth2AuthorizedClientManagerRegistrar` auto-registers a `DefaultOAuth2AuthorizedClientManager`
when unique `ClientRegistrationRepository` and `OAuth2AuthorizedClientRepository` beans exist
(which Boot provides). The chosen design is unaffected — the console defines the manager bean
explicitly (suppressing the registrar's), and that explicitness turned out to be load-bearing:
spring-security-test's `oidcLogin()` seam and the relay tests reach the manager only because
it is the unique bean (`ApiClientConfig` records why).

## M8c landing (2026-07-27) — five decisions taken while building the reconciliation surface

1. **The run history is the API's only descending listing.** The console needed a collection GET
   (`GET /api/v1/reconciliation-runs`) that PLAN §5 deliberately never had — the `/api/v1`
   namespace backstop was denying it, so the endpoint is a real core change with real ripples
   (matcher, I13 matrix rows, OpenAPI assertion, the layer-2 method-security cell). It pages
   **newest first**, unlike accounts and findings: an append-only operational log is read from
   its new end, and "what did the last sweep say?" must be page 0 rather than the last page of
   an unknown number. Ordering is descending **id**, not `started_at` — UUIDv7 makes them the
   same order, and V5 gives the table no index beyond its primary key, so a backwards scan of
   that key serves it with no sort node and no index to justify.
2. **The sweep trigger is authorized once, by the ledger.** `sec:authorize` hides the button
   from anyone without `LEDGER_ADMIN`, but the console adds **no** role rule of its own: a
   hand-rolled POST rides the user's token to the API and comes back 403, rendered as the
   problem document it is. A `hasRole` matcher in `ConsoleSecurityConfig` was considered and
   rejected — it would fork the role matrix into a second place that can drift from the
   authority. The console's job is to not *offer* what will be refused, not to re-adjudicate it.
   This is also the first exercise of `thymeleaf-extras-springsecurity6` (adopted at M8a on the
   BOM's word): the dialect is now **verified** working on Boot 4.1.0 with Security 7.1.
3. **Findings render as bare minor units.** The API's finding carries no currency, so there is
   no exponent the console may honestly apply — the one place the money contract deliberately
   does *not* apply, and the table says so rather than inventing a decimal point.
4. **The browser lane is the one verification task outside `check`.** `:console:e2eTest` needs a
   live compose stack *and* a host process on 8090, so wiring it in the way the root project
   wires `concurrencyTest` would break `./gradlew build` and the required "Console build" job.
   (Not the Docker image build — that runs `gradle :bootJar`, root-scoped, whose task graph
   contains no `check` at all.) The `@Tag("e2e")` marker and `tasks.test`'s matching `excludeTags`
   land together, deliberately: either alone is a broken lane. Playwright-Java is pinned at
   **1.61.0** — the latest Java binding on Maven Central (verified against repo1
   `maven-metadata.xml`, 2026-07-27); the npm line runs ahead at 1.62.0 and is not evidence for
   this artifact. Plain `@BeforeAll`/`@AfterAll`, never `@UsePlaywright` (experimental,
   upstream-tested only on Jupiter 5.14 — ADR-0005's discipline). Keycloak's stock-theme
   selectors (`#username`, `#password`, `#kc-login`) were re-verified against the running
   26.7.0 container at write time, and the browser navigates `localhost:8090`, never
   `127.0.0.1` — the realm's redirect-URI list is exact-match string comparison.
5. **The trigger answers two callers with one handler.** htmx gets `204 + HX-Redirect`; a
   JavaScript-off form post gets a plain `303`. Both land on the run just created. htmx would
   otherwise follow the 303 at the XHR level and hand a whole page to a swap target, and the
   confirm dialog has to be `hx-confirm` because an inline `onsubmit` needs a CSP this console
   deliberately does not grant.

Accepted trade-off, on record: the e2e lane's drift is seeded out of band by
`scripts/e2e-fixture.sh` (superuser SQL — ADR-0002's point is that nothing else *can* create
drift), so the lane depends on a fixture step rather than being self-contained. The alternative,
driving psql from inside the test, would put Docker orchestration in a browser test. Note that
`scripts/demo.sh --console` needs no such step and no deviation from the existing walkthrough:
the demo repairs the snapshot, but the DRIFT run and its finding remain — runs are append-only
and findings are write-once, so delta 7 is visible in the console permanently, which is a
better demonstration of the audit model than a drift left unrepaired would have been.

## M8-stretch landing (2026-07-27) — the console containerized, and two corrections to this ADR

The issuer-topology decision above chose "phase 1: host-run" and recorded the containerized
recipe as pre-research. Building it corrected that recipe twice, in the project's own favour.

1. **The ID-token issuer check does NOT have to be forgone.** The pre-research assumed a
   hand-built registration loses it, because the check was believed to compare `iss` against
   *discovery metadata* — which a registration that never discovered anything cannot have.
   Read from the artifact rather than from memory (the ADR-0005 discipline, applied to
   `OidcIdTokenValidator` in Security 7.1): it compares `iss` against
   `ClientRegistration.ProviderDetails.getIssuerUri()`. That is a field this project sets. The
   compose stack pins `KC_HOSTNAME` to the browser-facing URL, so the browser-facing issuer
   *is* the string Keycloak stamps into every token — set it and the check is fully armed.
   Note the shape of the near-miss: omitting the issuer would not have failed anything. Login
   would have worked, tests would have been green, and one validation would silently not be
   happening. `ConsoleOidcConfigTest` pins the field for that reason.
2. **Discovery was also buying a userinfo call nobody wanted.** `OidcUserService` fetches the
   userinfo endpoint whenever the registration has one and the request carries the `profile`
   scope — which discovery always supplied. The console reads nothing from it: roles come off
   the ID token by decision 6 above, and `preferred_username` rides the same token. A
   hand-built registration can decline, which discovery gave no way to say. One network leg
   removed from the login path, and one failure mode with it. This surfaced as a test failure
   the moment the production registration replaced the test one — the two had disagreed about
   userinfo since M8a, and the test was right.

**The split, and why the client needs one where the resource server didn't.** The console now
builds its single `ClientRegistration` by hand (`ConsoleOidcConfig`) from two URLs:
`browser-issuer-uri` (authorization, `end_session_endpoint`, and the issuer) and
`network-issuer-uri` (token, JWKS). On the host both are `localhost:8081` and nothing looks
split at all, which is the point — one code path, no profile fork, no configuration that only
exists in production. The core's resource server expressed the same idea in standard Boot
properties (`issuer-uri` plus an in-network `jwk-set-uri`); the OAuth2 *client* has no such
slot, because setting a provider `issuer-uri` is precisely what triggers the eager
`ClientRegistrations.fromIssuerLocation()` call at startup. Hence one project-namespaced
property block rather than half in Boot's namespace and half in ours, where neither file would
tell the whole story. The price, on record: Keycloak's `/protocol/openid-connect/*` URL layout
is now hardcoded instead of read from the provider.

**What killing discovery paid for, beyond the container.** The console context no longer
touches the network at startup, so: Keycloak stopped being a startup dependency (it fails fast
and loudly for a *wrong* URL, not for an absent one); the console image performs the same CDS
training run the core image does, which a context that dials a provider could not; and
`TestClientRegistrations` — a stand-in registration bean that existed only to suppress
discovery so tests could load a context — was deleted. Every console test now assembles the
PRODUCTION registration from the production yaml, and `WhoamiPageTest` asserts the logout
redirect against the running bean's own metadata rather than a constant copied beside it.

**Back-channel logout, the trade-off this ADR left open.** The decision outcome above recorded
logout as one-way, "deferred to the containerized stretch" because OIDC back-channel logout
needs a console URL Keycloak can reach and a container cannot dial a developer's host process.
Containerizing supplied the URL, so the deferral is closed rather than left standing:
`oidcLogout().backChannel()` plus the realm's `backchannel.logout.url` pointing at
`http://console:8090/...`. Three notes worth having in writing:

- The endpoint takes **no** `permitAll` and **no** CSRF exemption, and that absence is
  deliberate rather than an oversight: Security installs `OidcBackChannelLogoutFilter` *before*
  `CsrfFilter` and the filter answers the request itself, so neither CSRF nor authorization
  ever runs for it. A `permitAll` rule would imply a check that does not happen.
- Configuring `oidcLogout` is also what makes `oauth2Login` start recording sessions in the
  `OidcSessionRegistry`. Without it every logout token would validate correctly and match
  nothing — the quietest possible failure, which is why `ConsoleLoginCallbackTest` asserts the
  session is registered after a real login callback, and why the registry is an explicit bean
  (the precedent `ApiClientConfig` set with the authorized-client manager).
- It is **topology-dependent, by nature**: `console:8090` resolves only inside the compose
  network, so a host-run console is not reachable and Keycloak logs a failed notification.
  Front-channel RP-initiated logout is unaffected. The registry is also in-memory, so a
  multi-replica console would need a shared one — a logout token reaches exactly one replica.

**The drift badge, and the hazard it exposed.** The badge reports the newest sweep's verdict in
the topbar, polled by htmx every 15 seconds rather than rendered with the page: the topbar is on
every page, and an accounts listing should not pay for a reconciliation read or break when one
fails. It reports *what the last sweep said*, never a live claim about the data — nothing knows
whether an account has drifted until a sweep looks (ADR-0002), and a badge implying otherwise
would be the console's one dishonest pixel. A failed poll answers `204`, so the previous verdict
stays on screen; that silence is scoped to its own controller, because an `@ExceptionHandler`
that swallows failures is right for ambient chrome and wrong for a page a user asked for.

Putting a poller in the page chrome exposed a pre-existing bug in the M8b load-more, now fixed:
an htmx request on a dead session followed the login redirect at the XHR level, received
Keycloak's login page with a `200`, and swapped that HTML into whatever target asked for a
fragment. The console now answers `HX-Request` with `401` plus `HX-Refresh: true`, so htmx
reloads at the top level where a login bounce belongs. The *header* carries that meaning rather
than the status, because the console deliberately mirrors the ledger's own 401 elsewhere.

## Proof

- `WhoamiPageTest`: unauthenticated requests bounce to Keycloak; the `ops` session renders the
  `CONSOLE_OPS` bundle chip plus its three expanded roles and `viewer` renders exactly one
  chip (role-aware rendering, both directions); logout is a redirect to the realm's
  `end_session` endpoint carrying `id_token_hint` and the registered post-logout URI; logout
  without a CSRF token is refused.
- `ConsoleRealmRoleMapperTest`: the claim-shape table — missing, scalar, and non-string
  `realm_access` shapes all yield an authenticated-but-role-less session, never an exception;
  Keycloak's default roles never become authorities.
- `ConsoleApplicationTest`: the full production context assembles with no live Keycloak (the
  eager-discovery constraint, discharged by a real in-memory registration bean); health and
  the stylesheet answer anonymously while unknown paths and sibling actuator endpoints bounce
  to login — the surface pinned in both directions; and the production yaml's registration
  (id, `openid` scope, principal-name attribute) is bound and asserted directly, since the
  test registration bean otherwise shadows it.
- `ConsoleLoginCallbackTest`: the PRODUCTION login chain end to end — callback, token
  exchange, ID-token decode, the authorities-mapper wiring — with the two network legs
  stubbed at Spring Security's own bean seams; this is the test that fails if the
  `userAuthoritiesMapper` wiring is dropped (the console's counterpart to the core's
  matrix-plus-CI-smoke pairing).
- `ConsoleErrorDispatchTest`: the ERROR dispatch is permitted — a missing asset 404s plainly
  instead of bouncing its error render to Keycloak with a `;jsessionid`.
- CI: the "Console build" job runs the console's tests and its 0.70 gate on every push; the
  root build job is root-scoped in the same workflow file.
