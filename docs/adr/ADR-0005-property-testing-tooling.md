# ADR-0005: Property-based testing tooling — in-repo harness

- Status: Accepted
- Date: 2026-07-22
- Deciders: project owner + planning session

## Context and problem statement

The test strategy leans on property-based tests for the core invariants (entries balance,
balances equal the sum of postings, idempotent replays never double-post — see the
[guarantee table](../../README.md#the-guarantees)). The original plan named **jqwik**, the de-facto standard
JVM property-testing library. Planning-time compatibility research surfaced three blocking facts:

1. **Platform incompatibility.** Spring Boot 4.1 manages JUnit 6 (Platform 6.0.3). jqwik 1.10.1
   (latest, 2026-05-29) is a TestEngine built against JUnit Platform 1.14.4, and JUnit 6 makes no
   binary-compatibility guarantee for Platform-1.x engines. No Platform-6 jqwik release exists.
2. **End-of-life signal.** jqwik's own release notes describe Platform-6 support as coming in
   "upcoming releases, *if ever realised*".
3. **Anti-AI Usage Clause.** jqwik 1.10+ ships an explicit clause discouraging use of the library
   with AI coding agents. This project is developed with AI assistance; using the library would
   knowingly go against the maintainer's stated wishes, independent of whether the clause is
   legally binding.

So the question became: how do we keep the property-based testing *discipline* without the
standard tool?

## Decision drivers

- Property tests are load-bearing for this project's whole premise (provable invariants) — they
  cannot be quietly downgraded to a handful of example-based cases.
- The test stack must run natively on JUnit 6 / Boot 4.1 without a parallel legacy JUnit universe.
- Respect the jqwik maintainer's explicit wishes regarding AI-assisted projects.
- Failures must be **reproducible**: a failing random test that cannot be re-run deterministically
  is worse than no test.
- Keep the dependency surface honest: a dormant library in the correctness-critical path is a
  liability a ledger cannot argue away.

## Considered options

1. In-repo property-testing harness on JUnit Jupiter 6 — **chosen**
2. jqwik 1.10.1 quarantined in an isolated Gradle test source set pinning JUnit Platform 1.x
3. Another JVM property-testing library (Kotest property engine, QuickTheories, junit-quickcheck, jetCheck)

## Decision outcome

**Option 1: build a small, owned property harness** in test-support code
(`src/test/java/...∕support/property`), on plain JUnit Jupiter 6. Decisive reasons: it is the only
option that is simultaneously JUnit-6-native, dependency-risk-free, and respectful of the jqwik
maintainer's wishes — and its cost is modest because the ledger's domain types are simple to
generate (longs, currencies, small object graphs), so we need perhaps 10% of what jqwik offers.

### Harness contract (v1)

- `Gen<T>`: composable generators over a seeded `SplittableRandom`
  (`map`, `flatMap`, `listOf(min,max)`, `oneOf`, `frequency`); domain generators for `Money`
  (including 0- and 3-exponent currencies), account graphs, and balanced/unbalanced entry drafts.
- `Property.check(gen, invariant)`: runs N iterations (default 200; overridable via system
  property `ledger.property.iterations`; CI can raise it).
- **Seed discipline**: every run derives per-iteration seeds from a root seed; on failure the
  harness throws an assertion error containing the root seed and iteration, and
  `ledger.property.seed=<seed>` deterministically replays the exact failing case.
- **Shrinking (basic)**: numeric values shrink toward zero, lists toward fewer elements, via
  bounded re-check passes. Deliberately simple; if a counterexample is hard to read, we improve
  the generator, not the shrinker.
- **Stateful model testing** (M5): a command-sequence runner (generate `CreateAccount` /
  `Transfer` / `Freeze` / `Reverse` commands, apply to both an in-memory model ledger and the real
  service on Testcontainers PostgreSQL, compare observable state after every step). **Landed M5
  (2026-07-26)** as `StatefulModelPropertyIntegrationTest` + `support/model/ModelLedger`:
  commands address accounts by INDEX because generation is a pure function of the rng while
  real account ids are only born at execution time. Counterexamples are reported UNSHRUNK —
  a scenario is a record, and this harness's shrinker covers numeric/list shapes only (the
  contract above) — so seed replay, not minimization, is the reproduction story. The model
  mirrors the service's pinned decision order (I11 → per-leg currency/status →
  canonical-order overdraft, first offender compared too) and its teeth were proven by
  mutation before trust (a model with a rule deleted fails the suite with a replayable seed).
- The harness itself is test-covered (meta-tests: a deliberately false property must fail and
  report a replayable seed; shrinking must terminate).

### Consequences

- Positive: zero external risk in the correctness-critical path; native JUnit 6; the harness is
  ~300 lines we fully understand — and explaining *why it exists* is itself a strong interview
  story about dependency due diligence.
- Positive: generators live next to the domain and evolve with it; no framework-fighting.
- Negative: no sophisticated shrinking, no statistical coverage reports (`Statistics.collect`),
  no exhaustive-mode generation. Accepted: our invariants are universally quantified equalities,
  where naive shrinking is usually adequate.
- Negative: the harness is code we must maintain and test. Mitigated by keeping the API surface
  deliberately tiny and versioning it with the tests that use it.
- If a JUnit-6-compatible, actively maintained jqwik (or successor) later appears **and** the
  usage-clause situation changes, migrating is mechanical: `Gen<T>` maps onto `Arbitrary<T>`.
  That would be a superseding ADR.

### Proof

- The [guarantee table](../../README.md#the-guarantees) marks which invariants are
  property-proven (I1, I2, I4, I5, I8, I10 at minimum) — those tests are written against the
  harness API.
- Harness meta-tests (failing-property seed replay, shrinking termination) ship with the harness
  itself in M2, before any invariant depends on it.

## Pros and cons of the options

### Option 1 — in-repo harness (chosen)

- Good: JUnit-6-native; no dependency/governance risk; tiny, comprehensible; interview-defensible.
- Bad: reinvents a (small) wheel; weaker shrinking and no statistics; maintenance is ours.
- Failure mode to watch: harness bugs masking real defects — mitigated by meta-tests and by
  keeping invariant assertions independent of harness internals.

### Option 2 — jqwik in an isolated source set

- Good: mature shrinking, exhaustive generation, statistics; the original plan.
- Bad: requires a quarantined test universe pinning JUnit Platform 1.14.4/Jupiter 5.x alongside
  JUnit 6 — two test frameworks to keep coherent indefinitely; the library signals it may never
  support Platform 6; and using it would disregard the maintainer's explicit anti-AI-usage wish in
  a project that is openly AI-assisted.
- Failure mode: a future Boot/Gradle upgrade silently breaks the pinned legacy platform and the
  property suite stops running — the worst kind of test rot (green because absent).

### Option 3 — alternative library

- Kotest property engine: actively maintained, but pulls Kotlin (compiler, stdlib, idiom) into a
  deliberately-Java portfolio project — the tail wagging the dog.
- QuickTheories / junit-quickcheck / jetCheck: dormant for years and/or JUnit-4-era; strictly
  worse than jqwik on the axes that disqualified jqwik.
- Good: none of these beat option 1 on any decision driver; recorded for completeness.

## References

- jqwik 1.10.1 release notes (Platform 1.14.4 basis; "if ever realised"; Anti-AI Usage Clause):
  https://github.com/jqwik-team/jqwik/releases/tag/1.10.1
- Spring Boot 4.1.0 dependency management (JUnit BOM 6.0.3):
  https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.0/spring-boot-dependencies-4.1.0.pom
- ArchUnit's parallel Platform-6 issue (context for the wider JUnit 6 engine gap):
  https://github.com/TNG/ArchUnit/issues/1556
