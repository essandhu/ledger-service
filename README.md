# Ledger Service

[![CI](https://github.com/essandhu/ledger-service/actions/workflows/ci.yml/badge.svg)](https://github.com/essandhu/ledger-service/actions/workflows/ci.yml)

A standalone, production-grade **double-entry ledger service** (Java 21 · Spring Boot 4.1 ·
PostgreSQL 18). The differentiator: **every guarantee is backed by an automated test that proves
it** — balanced entries, immutable history, overdraft safety under concurrency, idempotent writes,
and reconciliation that turns "could the balance drift?" into a monitored metric.

## The guarantees

Seventeen invariants, each with an ID that appears verbatim in the display names of the tests
that prove it — `grep -r "I4" src/test` maps any claim below to its proofs. The contested design
decisions behind them live in [docs/adr/](docs/adr/README.md), each ADR ending with the tests
that enforce it.

| ID | Guarantee | Proof (primary) | Lane |
|---|---|---|---|
| I1 | Every entry's postings sum to **exactly zero per currency** — integer equality, no epsilon | [EntryDraftTest](src/test/java/io/github/essandhu/ledger/domain/model/EntryDraftTest.java) · [EntryDraftPropertyTest](src/test/java/io/github/essandhu/ledger/domain/model/EntryDraftPropertyTest.java) | unit + property |
| I2 | ≥ 2 postings per entry, every amount nonzero — also a DB `CHECK` | [EntryDraftTest](src/test/java/io/github/essandhu/ledger/domain/model/EntryDraftTest.java) · [JournalSchemaIntegrationTest](src/test/java/io/github/essandhu/ledger/JournalSchemaIntegrationTest.java) | unit + integration |
| I3 | Entries and postings are **immutable** — the runtime DB role cannot `UPDATE`/`DELETE` them | [PostingImmutabilityIntegrationTest](src/test/java/io/github/essandhu/ledger/PostingImmutabilityIntegrationTest.java) | integration (real privilege errors) |
| I4 | Snapshot balance **equals Σ postings** after every committed transaction | [PostingConservationPropertyIntegrationTest](src/test/java/io/github/essandhu/ledger/PostingConservationPropertyIntegrationTest.java) · [StatefulModelPropertyIntegrationTest](src/test/java/io/github/essandhu/ledger/StatefulModelPropertyIntegrationTest.java) | property + model-vs-SUT |
| I5 | **Conservation**: the whole ledger sums to zero per currency, always | [PostingConservationPropertyIntegrationTest](src/test/java/io/github/essandhu/ledger/PostingConservationPropertyIntegrationTest.java) | property |
| I6 | `allowNegative=false` accounts **never** observe a negative natural balance — under any interleaving | [OverdraftRaceConcurrencyTest](src/test/java/io/github/essandhu/ledger/concurrency/OverdraftRaceConcurrencyTest.java) | concurrency hammer |
| I7 | **No lost updates**: N concurrent deposits land exactly N times | [DepositRaceConcurrencyTest](src/test/java/io/github/essandhu/ledger/concurrency/DepositRaceConcurrencyTest.java) | concurrency hammer |
| I8 | **Idempotent replay**: one entry ever per (principal, key, payload); replays return the original, marked `Idempotency-Replayed: true` | [IdempotencyApiIntegrationTest](src/test/java/io/github/essandhu/ledger/adapter/web/IdempotencyApiIntegrationTest.java) · [IdempotencyRaceConcurrencyTest](src/test/java/io/github/essandhu/ledger/concurrency/IdempotencyRaceConcurrencyTest.java) | integration + concurrency |
| I9 | **Idempotency conflict**: same key, different payload → `422`, zero side effects | [IdempotencyPropertyIntegrationTest](src/test/java/io/github/essandhu/ledger/IdempotencyPropertyIntegrationTest.java) | property |
| I10 | **As-of consistency**: `asOf(t2) − asOf(t1) = Σ postings in (t1, t2]` | [AsOfConsistencyPropertyIntegrationTest](src/test/java/io/github/essandhu/ledger/AsOfConsistencyPropertyIntegrationTest.java) | property |
| I11 | A **reversal** negates its original exactly, at most once — including under a double-reversal race | [ReversalApiIntegrationTest](src/test/java/io/github/essandhu/ledger/adapter/web/ReversalApiIntegrationTest.java) | integration |
| I12 | **Posting integrity**: currency match, `ACTIVE`-only postings, `CLOSED` is terminal and requires zero balance | [LifecycleVsPostingIntegrationTest](src/test/java/io/github/essandhu/ledger/adapter/web/LifecycleVsPostingIntegrationTest.java) · [AccountTest](src/test/java/io/github/essandhu/ledger/domain/model/AccountTest.java) | unit + integration |
| I13 | **AuthZ matrix**: every endpoint × {no token, wrong role, right role} — one parameterized table, extended every milestone | [AuthzMatrixIntegrationTest](src/test/java/io/github/essandhu/ledger/config/AuthzMatrixIntegrationTest.java) | integration (minted JWTs) |
| I14 | **Architecture**: hexagonal dependency rules — domain depends on the JDK only; `float`/`double` banned from the core | [HexagonalArchitectureTest](src/test/java/io/github/essandhu/ledger/architecture/HexagonalArchitectureTest.java) | ArchUnit |
| I15 | **Reconciliation detects drift**: a snapshot corrupted out-of-band is flagged within one run — finding row + gauges | [ReconciliationJobIntegrationTest](src/test/java/io/github/essandhu/ledger/adapter/reconciliation/ReconciliationJobIntegrationTest.java) | integration (seeded drift) |
| I16 | **Migrations are sound**: clean-migrate from empty, immutable checksums, grants match I3 after every migration | [WalkingSkeletonTest](src/test/java/io/github/essandhu/ledger/WalkingSkeletonTest.java) · schema suites | integration |
| I17 | **Deadlock-freedom** under sustained bidirectional transfer hammering — any `40P01` fails the test | [BidirectionalTransferConcurrencyTest](src/test/java/io/github/essandhu/ledger/concurrency/BidirectionalTransferConcurrencyTest.java) | concurrency hammer |

Three proofs are DB-shaped rather than code-shaped, on purpose: I3 is a **grant**, not a code
path (the app's own role gets `permission denied` — try it, the demo does); I8's backstop is a
**partial unique index** that makes double-posting unable to commit regardless of application
bugs; I16 asserts the grants after every migration so neither can silently regress.

## Why this design

Common ad-hoc money mistakes, and what this ledger does instead:

| Ad-hoc failure | Ledger answer |
|---|---|
| `double` arithmetic loses cents | Integer minor units end-to-end: `long` → `BIGINT` → JSON integer ([ADR-0001](docs/adr/ADR-0001-money-representation.md)) |
| Single-row "transfers" | Balanced multi-leg journal entries — money moves, it never appears |
| `UPDATE balance = ...` in place | Append-only postings; the snapshot is derived, serialized under ordered locks ([ADR-0002](docs/adr/ADR-0002-balance-storage.md), [ADR-0003](docs/adr/ADR-0003-concurrency-control.md)) |
| Client retries double-charge | `Idempotency-Key` with byte-identical replay ([ADR-0004](docs/adr/ADR-0004-idempotency.md)) |
| Trusted stored balances | A scheduled-able reconciliation sweep recomputes everything and gauges the drift (I15) |
| `UPDATE`/`DELETE` as "fixes" | Immutable entries + linked reversals; the runtime role *cannot* mutate history (I3) |

Architecture is hexagonal (ports & adapters) in a single Gradle module — the boundaries are
enforced by [ArchUnit rules that fail the build](src/test/java/io/github/essandhu/ledger/architecture/HexagonalArchitectureTest.java),
not by convention: a framework-free domain core, use-case services owning the transaction
boundary, and Spring/JPA/web/Batch confined to adapters. Property-based testing runs on an
in-repo harness ([ADR-0005](docs/adr/ADR-0005-property-testing-tooling.md) explains why not
jqwik on JUnit Platform 6).

## API

Base path `/api/v1`; errors are RFC 9457 `application/problem+json` with stable type slugs
(`…/problems/overdraft`, `…/problems/idempotency-key-conflict`, …). Bearer JWT everywhere except
`/actuator/health`; **no role hierarchy** — ADMIN cannot read, WRITE cannot scrape, composite
roles in Keycloak are the convenience path.

| Method & path | Role |
|---|---|
| `POST /accounts` · `PATCH /accounts/{id}` | `LEDGER_ADMIN` |
| `GET /accounts` · `GET /accounts/{id}` | `LEDGER_READ` |
| `POST /transfers` · `POST /journal-entries` · `POST /journal-entries/{id}/reversal` | `LEDGER_WRITE` |
| `GET /journal-entries/{id}` | `LEDGER_READ` |
| `GET /accounts/{id}/balance[?at=…]` — O(1) snapshot; as-of recomputed from postings | `LEDGER_READ` |
| `GET /accounts/{id}/postings` — keyset-paginated, account-bound opaque cursor | `LEDGER_READ` |
| `POST /reconciliation-runs` — synchronous sweep, `201` whatever the verdict | `LEDGER_ADMIN` |
| `GET /reconciliation-runs/{id}[/findings]` | `LEDGER_READ` |
| `GET /actuator/metrics` · `GET /actuator/prometheus` | `LEDGER_METRICS` ([ADR-0006](docs/adr/ADR-0006-observability-exposure.md)) |

**Idempotency in one paragraph** (full text in the OpenAPI spec, [ADR-0004](docs/adr/ADR-0004-idempotency.md)):
the three money movers require an `Idempotency-Key` (comma-free, ≤ 200 chars). Successful
replays return `200` + the original body byte-for-byte + `Idempotency-Replayed: true`; a reused
key with a different payload is a `422` conflict with zero side effects. Two deliberate
deviations from the IETF draft: concurrent duplicates briefly **block and answer definitively**
(no `409`-while-in-flight), and only successes are recorded — a `422` today can become a `201`
on a corrected retry, so responses for a key are not immutable over time.

The OpenAPI spec is generated from the code, asserted by
[OpenApiDocumentationTest](src/test/java/io/github/essandhu/ledger/OpenApiDocumentationTest.java),
and published as a CI artifact on every build — it cannot be stale. Swagger UI at
`/swagger-ui.html` (any authenticated token).

## Quickstart

Requirements: Docker (that's it — the JDK is toolchain-resolved, Gradle comes from the wrapper;
the demo script additionally wants `jq`).

```sh
docker compose up -d --build --wait   # PostgreSQL 18 + Keycloak (realm pre-provisioned) + the service
./gradlew build                       # compile + every suite in both lanes + the coverage gate
```

Dev endpoints: service `http://localhost:8080` · Keycloak `http://localhost:8081`
(realm `ledger`, demo clients `ledger-cli` / `ledger-readonly` / `ledger-metrics`) · Keycloak
health `http://localhost:9000/health/ready`.

### The demo

```sh
scripts/demo.sh                  # fresh stack, then the whole story end-to-end
scripts/demo.sh --observability  # same, plus Prometheus + Grafana
```

One scripted pass over everything above, with real Keycloak tokens: the role matrix (including
the 403s), a balanced transfer, the rejection vocabulary (unbalanced, overdraft, frozen), a
`permission denied` from PostgreSQL itself when the app's role tries to `UPDATE` a posting,
balances and as-of algebra, idempotent replay / tamper conflict, exact reversal — and the
finale: a superuser corrupts a snapshot **out-of-band**, the reconciliation sweep convicts it
(finding row, delta, Prometheus gauges), the snapshot is repaired by recomputation, and the
final sweep reads CLEAN again.

## Observability

Micrometer → `/actuator/prometheus`, guarded by the dedicated `LEDGER_METRICS` role — the
scraper can scrape and do nothing else ([ADR-0006](docs/adr/ADR-0006-observability-exposure.md)
records why a role beat a management-port split here, and why the Prometheus scrape uses OAuth2
client-credentials instead of a static token).

| Metric | What it tells you |
|---|---|
| `ledger.reconciliation.drift.accounts` / `.drift.absolute` | **The headline gauges**: accounts adrift and Σ\|delta\| at the last completed run |
| `ledger.reconciliation.runs{outcome}` / `.duration` | Sweep outcomes (`clean`/`drift`/`failed`) and duration |
| `ledger.posting.duration{entry_type,outcome}` | Posting latency, split by entry type and posted/rejected |
| `ledger.posting.rejected{reason}` | Rejections by problem-type slug, verbatim |
| `ledger.posting.lock.wait` | Time in the ordered `FOR UPDATE` acquisition — the hot-account signal |
| `ledger.idempotency.replayed` / `.conflict` | Retries answered from the record; tampered reuses |

`docker compose --profile observability up -d` adds Prometheus (v3.13) and Grafana (13.1) with a
provisioned dashboard (anonymous viewer at `http://localhost:3000`). Teardown needs the profile
too: `docker compose --profile observability down`.

> **Upgrading an existing stack:** Keycloak's `--import-realm` skips realms that already exist,
> so a pre-M7 `pgdata` volume has neither the `LEDGER_METRICS` role nor the `ledger-metrics`
> client — metric calls 403 and the Prometheus scrape fails until you reset with
> `docker compose down -v` (or run `scripts/demo.sh`, which always starts fresh).

## Testing & CI

| Lane | Contents | Where |
|---|---|---|
| default (`./gradlew test`) | unit, property, ArchUnit, Testcontainers integration (one context, one PostgreSQL) | CI "Build, test, coverage gate" |
| stress (`./gradlew concurrencyTest`) | the I6/I7/I8/I17 hammers — never cached, re-samples interleavings every run | CI "Concurrency proof" |
| smoke | compose up + scripted probes over the real Keycloak issuer, including the drift demo | CI "docker-compose smoke test" |

The JaCoCo gate is `check`-fatal at **0.90 line coverage** overall, with a second exact rule:
the domain packages hold **100%** — every uncovered domain line is treated as either a missing
proof or code that should not exist. (The only uncovered code in the whole codebase is the
bootstrap `main()` and a catch for a JRE that lacks SHA-256, which the Java spec forbids —
four lines, both outside domain.) Mutation testing (PIT) is wired as a non-gating report:
`./gradlew pitest`.

## Stack

Java 21 · Spring Boot 4.1 (Framework 7, Security 7.1, Batch 6, JUnit 6) · PostgreSQL 18 ·
Keycloak 26.7 · Gradle 9.6 · Flyway · Testcontainers 2 · springdoc 3 · JaCoCo · PIT.
Versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml) with the
reasoning inline; everything Boot-BOM-managed is deliberately not repeated there.

Deliberate v1 scope: single currency per account (multi-currency entries balance per currency);
no FX, holds, or multi-tenancy — the future-ADR candidates are listed in
[docs/adr/README.md](docs/adr/README.md).

All credentials in this repo are dev/demo-only values for the local compose stack.
