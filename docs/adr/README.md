# Architecture Decision Records

Decisions that were genuinely contested — where a competent engineer could reasonably have chosen
differently — are recorded here with the options, the trade-offs, and the reasons the choice fell
where it did. Settled best practice (e.g. "use migrations", "don't store money as floats" — well,
see ADR-0001 for why even that one earned a record) is documented in [PLAN.md](../PLAN.md) instead.

Format: [MADR](https://adr.github.io/madr/)-style, one file per decision, immutable once
`Accepted` — superseding decisions get a new number and cross-link.

> Note: the ADRs reference `PLAN.md` and `TEST-STRATEGY.md` (the milestone plan and the invariant
> catalogue, e.g. the `I`-numbered invariants in each Proof section). Those are local working
> documents, deliberately untracked; each ADR's Proof section restates the invariants it relies
> on, so the ADRs stand on their own.

## Index

| ADR | Title | Status |
|---|---|---|
| [ADR-0001](ADR-0001-money-representation.md) | Money representation — integer minor units | Accepted |
| [ADR-0002](ADR-0002-balance-storage.md) | Balance storage — maintained snapshot, postings as source of truth | Accepted |
| [ADR-0003](ADR-0003-concurrency-control.md) | Concurrency control for postings — pessimistic ordered row locks | Accepted |
| [ADR-0004](ADR-0004-idempotency.md) | Idempotency key design and retention | Accepted |
| [ADR-0005](ADR-0005-property-testing-tooling.md) | Property-based testing tooling — in-repo harness | Accepted |

## Future ADR candidates

Explicitly out of scope for v1; each becomes an ADR when (and only when) the need is real:

- **Multi-tenancy** — tenant isolation model (schema-per-tenant vs row-level vs separate DBs).
- **Currency conversion / FX** — conversion entries, rate sourcing, rounding & allocation policy
  (largest-remainder distribution; see the note in ADR-0001).
- **Authorization holds / two-phase postings** — pending entries that reserve balance before
  capture.
- **Bitemporal effective dating** — separating "when it happened" from "when we recorded it".
- **Event publishing** — transactional outbox for downstream consumers.
- **Posting archival & partitioning** — table partitioning by time, checkpoint-based balance
  derivation (option C of ADR-0002 becomes relevant here).
- **Multi-instance scheduling** — leader election / locking for jobs when the service scales past
  one instance (v1 documents a single-instance assumption for schedulers; Spring Batch metadata
  already prevents concurrent duplicate job *instances*).
- **API versioning & deprecation policy** — once there is a real external consumer.

## Template

```markdown
# ADR-NNNN: <title>
- Status: Proposed | Accepted | Superseded by ADR-MMMM
- Date: YYYY-MM-DD
- Deciders: <who>

## Context and problem statement
## Decision drivers
## Considered options
## Decision outcome
### Consequences
### Proof            <!-- the automated tests that enforce this decision (invariant IDs from TEST-STRATEGY.md) -->
## Pros and cons of the options
## References
```
