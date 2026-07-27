# ADR-0006: Observability exposure — a dedicated metrics role, not a management port

- Status: Accepted
- Date: 2026-07-26 (M7)
- Deciders: project owner + M7 session

## Context and problem statement

From M1 through M6 the metric surfaces (`/actuator/metrics`, `/actuator/prometheus`) sat on the
application port behind `anyRequest().authenticated()` — reachable by ANY authenticated
principal, including one holding zero `LEDGER_*` roles. PLAN §5 had sketched
"management-port-internal" as the target; the deviation was recorded in `SecurityConfig`'s
javadoc and deferred to M7 as ops polish. M7 also adds the observability stack (Prometheus +
Grafana in a compose profile), which turns the question concrete: *who may scrape, and how does
the scraper authenticate?*

Two sub-decisions, resolved together:

1. **How is the metric surface protected?**
2. **How does Prometheus authenticate its scrape?**

## Decision drivers

- The test harness is the constitution: `@LedgerIntegrationTest` is MockMvc in a MOCK web
  environment — one Spring context, one PostgreSQL container, deliberately. Whatever ships must
  be provable inside that harness.
- No role hierarchy anywhere (PLAN §7): ADMIN cannot read, WRITE cannot read — a metrics
  decision that quietly reintroduced "any strong principal can scrape" would break the posture.
- The compose healthcheck, the CI smoke job, and the demo script all probe actuator endpoints
  from specific vantage points; an "internal" port that must be published anyway is theater.
- Prometheus must keep scraping unattended for longer than a token lifespan (the realm's
  `accessTokenLifespan` is 900 s).

## Considered options

1. **Dedicated `LEDGER_METRICS` role on the shared port — chosen.**
2. **Management-port split** (`management.server.port`, port unpublished in compose). Rejected:
   a separate management port only materializes in a real servlet container, so the MockMvc
   harness cannot reach it at all — proving the rule would require a RANDOM_PORT context fork
   (second container, breaking the one-context economics) or going unproven; it also moves
   `/actuator/health` out from under the compose healthcheck and the anonymous-health contract
   (I13's one permitted anonymous surface); and the CI smoke and demo scrape from the host, so
   the port would be published in compose anyway — at which point "internal" is a label, not a
   boundary. `EndpointRequest` matchers are path-based, not port-based, so a permitAll chain
   "scoped to the management port" silently opens the same paths on 8080 in every test slice.
3. **Leave the M1 deviation standing.** Rejected: metrics leak operational shape (drift gauges,
   rejection reasons, throughput); "any authenticated principal" includes the read-only demo
   client — the least-privilege story the rest of the service tells would end at the actuator.

For the scrape credential:

1. **OAuth2 client_credentials in Prometheus's scrape config — chosen.** A dedicated confidential
   client `ledger-metrics` whose service account holds ONLY `LEDGER_METRICS`; Prometheus mints
   and refreshes tokens itself against the in-network Keycloak token endpoint (supported in
   `scrape_configs` since Prometheus 2.27).
2. **Static bearer token via `authorization.credentials(_file)`.** Rejected: the realm's tokens
   die after 900 s, so something external must keep re-minting and rewriting the credential —
   an ops liability with no upside over the oauth2 block.
3. **Unauthenticated scrape endpoint.** Rejected with option 2 above — it is the same decision
   wearing different clothes.

## Decision outcome

`/actuator/metrics` and `/actuator/prometheus` require the dedicated **`LEDGER_METRICS`** realm
role — both together, because they expose the same registry in different formats and gating one
without the other would be incoherent. The rule keeps the no-hierarchy posture: ADMIN scrapes
nothing, and the scraper's service account (`ledger-metrics`, client-credentials only) can
scrape and do nothing else. `ledger-cli` (the demo/smoke client) additionally carries the role
so CI's drift-demo scrapes keep working. Health stays the single anonymous endpoint; `/actuator/info`
and the springdoc surfaces stay any-authenticated.

Accepted trade-off, on record: with oauth2 in the scrape path, Keycloak becomes a dependency of
metrics collection — an IdP outage pauses scraping (Prometheus's docs caution exactly this for
critical monitoring). For this single-instance demo stack that is the right price for a scraper
that cannot touch the ledger; a production deployment with a hard "metrics must survive IdP
outages" requirement would revisit option 2 of the exposure question with a real internal
network boundary instead of compose port-publishing.

The observability stack itself ships as compose profile `observability` (Prometheus v3.13 LTS,
Grafana 13.1, provisioned datasource + dashboard) so the default stack stays lean; teardown
requires naming the profile (`docker compose --profile observability down`).

## Proof

- `AuthzMatrixIntegrationTest` (I13): `/actuator/metrics` and `/actuator/prometheus` each pinned
  for {no token → 401, role-less token → 403, `LEDGER_ADMIN` → 403, `LEDGER_METRICS` → 200} —
  the wrong-role cells are the no-hierarchy proof — plus the reverse direction: a
  `LEDGER_METRICS`-only principal gets 403 on an API read and on the reconciliation trigger
  (the scraper can scrape and do nothing else, proven both ways).
- CI smoke: the drift-demo gauges are scraped with a `LEDGER_METRICS`-bearing token; the
  unauthenticated-401 probes still pass.
- `scripts/demo.sh`: scrapes with the READ-only token first and asserts the 403 before showing
  the gauges with the metrics-bearing token.
