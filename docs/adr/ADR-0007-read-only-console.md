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
