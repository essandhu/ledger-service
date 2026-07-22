# Ledger Service

A standalone, production-grade **double-entry ledger service** (Java 21 · Spring Boot 4.1 ·
PostgreSQL 18). The differentiator: **every guarantee is backed by an automated test that proves
it** — balanced entries, immutable history, overdraft safety under concurrency, idempotent writes,
and reconciliation that turns "could the balance drift?" into a monitored metric.

> **Status: planning complete, pre-M0.** Implementation is test-first and milestone-driven;
> no domain code exists yet by design.

## Architecture

Hexagonal (ports & adapters): a framework-free domain core, use-case services owning the
transaction boundary, and Spring/JPA/web/batch confined to adapters — with the boundaries
enforced by ArchUnit tests, not convention. The contested decisions are recorded with their
trade-offs in [docs/adr/](docs/adr/README.md): money representation, balance storage,
concurrency control, idempotency, and property-testing tooling. Each ADR ends with the
automated tests that prove the decision holds.

## Quickstart

Requirements: Docker (that's it — the JDK is toolchain-resolved, Gradle comes from the wrapper).

```sh
docker compose up -d --wait   # PostgreSQL 18 + Keycloak (realm pre-provisioned) + the service
./gradlew build               # compile + all test suites + coverage gate
```

Dev endpoints: service `http://localhost:8080` · Keycloak `http://localhost:8081`
(realm `ledger`, demo client `ledger-cli`) · Keycloak health `http://localhost:9000/health/ready`.

All credentials in this repo are dev/demo-only values for the local compose stack.
