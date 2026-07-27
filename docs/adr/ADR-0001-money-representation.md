# ADR-0001: Money representation — integer minor units

- Status: Accepted
- Date: 2026-07-22
- Deciders: project owner + planning session

## Context and problem statement

The ledger records signed monetary amounts on postings and must guarantee that every journal
entry sums to exactly zero per currency ([invariant I1](../../README.md#the-guarantees)). That invariant is checked
in the domain on every write, re-checked by SQL during reconciliation, and asserted by property
tests — so the amount representation must make "sums to zero" a question with an exact answer in
every layer: Java domain model, PostgreSQL storage, and the JSON API.

Complicating detail: the number of decimal places is **per currency**. ISO 4217 assigns each
currency a minor-unit exponent, maintained by SIX (the official ISO 4217 maintenance agency):
JPY has 0 minor units, most currencies (EUR, USD) have 2, and BHD has 3 [1][2]. Any fixed-scale
decimal scheme is wrong for some subset of currencies; any floating-point scheme is wrong for
all of them.

The question: how are amounts represented in the domain (`domain.model.Money`), the database
(`posting.amount`, `account_balance.balance`), and on the wire?

## Decision drivers

- **Exactness**: invariant I1 (entries balance to zero per currency) must be checkable with
  exact equality — an epsilon comparison in a ledger is an admission of imbalance.
- **One representation end-to-end**: every conversion between layers (parse, rescale, round) is
  a place where money can silently change value.
- **Per-currency exponents**: JPY (0), EUR/USD (2), BHD (3) must all be representable without
  special cases in arithmetic.
- **Hot-path performance**: `SUM(amount)` over postings (as-of balances, full reconciliation
  recompute) and snapshot bumps run constantly.
- **Interoperability**: clients integrating a ledger API benefit from a convention they already
  know from payment providers.
- **Enforceability**: the choice must be provable by automated tests (ArchUnit, property tests, SQL).

## Considered options

- **A. Integer minor units** — `long` in Java, `BIGINT` in PostgreSQL, JSON integer, paired with an ISO 4217 code. **(chosen)**
- **B. `BigDecimal` + `NUMERIC(19,4)`** — the classic Java enterprise answer.
- **C. Decimal strings end-to-end** — store `NUMERIC`, transmit `"12.50"`.
- **D. IEEE-754 `double`** — the anti-option, included to document why it is banned.

## Decision outcome

**Option A.** Amounts are integers in the currency's minor unit: `1099` means €10.99, ¥1099, or
BHD 1.099 depending on the paired currency code. Decisive reasons:

1. **The ledger's arithmetic is closed over integers.** v1 only adds, negates, and compares
   amounts — operations that never produce a fraction. Integer minor units make I1 literally
   `SUM(amount) = 0` in SQL and `total == 0L` in Java, with no rounding mode, scale, or epsilon
   anywhere in the system, because no operation can create one.
2. **One value, every layer.** `long` in the domain, `BIGINT` in PostgreSQL, a JSON integer on
   the wire. Nothing is parsed, rescaled, or rounded at any boundary. This is also the
   established payment-industry convention: Stripe's API takes amounts in the currency's
   smallest unit (1099 for $10.99, 500 for ¥500, 250 for KWD 0.250), with a handful of documented
   legacy exceptions (ISK/UGX carried in two-decimal form; three-decimal amounts must end in
   0) [3], and Square's `Money`
   object is an integer amount in the currency's base unit plus an ISO 4217 code [4].
3. **Fastest representation for the hot paths.** PostgreSQL's own documentation warns that
   "calculations on `numeric` values are very slow compared to the integer types" [5];
   the reconciliation job recomputes `SUM(amount)` for every account, and `BIGINT` makes that
   a native 64-bit aggregation.

Concretely:

- Domain: `Money` is a record in `io.github.essandhu.ledger.domain.model` — a `long` amount plus
  a currency — whose `plus`/`negate` reject mixed currencies and use `Math.addExact` /
  `Math.negateExact`, which throw `ArithmeticException` on 64-bit overflow instead of wrapping [6].
- Database: `posting.amount BIGINT NOT NULL CHECK (amount <> 0)`, signed, debit-positive
  ([V3__journal.sql](../../src/main/resources/db/migration/V3__journal.sql));
  `account_balance.balance BIGINT`.
- API: `{"amount": 1099, "currency": "EUR"}`; fractional or non-numeric `amount` is rejected
  with a 400/422 problem. The API never renders `"10.99"` — display formatting is a client
  concern, driven by the ISO 4217 exponent.
- Enforcement: an ArchUnit rule bans `float`/`double` from `domain` and `application` packages
  outright (invariant I14).

### Consequences

Positive:

- Zero-sum validation is exact integer equality everywhere — domain check, SQL reconciliation,
  property suite. There is no epsilon constant in the codebase to tune or defend.
- Overflow is loud (`ArithmeticException`), never silent wraparound; drift gauges
  (`ledger.reconciliation.drift.absolute`) report in the same unit stored on disk.
- The wire format matches what integrators already handle for Stripe/Square, and the DB column
  supports index-only `SUM` scans.
- Headroom is enormous: `Long.MAX_VALUE` is 9,223,372,036,854,775,807 minor units ≈ 9.2 × 10^18 —
  about $92 quadrillion in cents, roughly 800× gross world product. No plausible balance
  approaches it.

Negative (accepted costs):

- **Raw values are human-hostile.** `1099` in a `posting` row is €10.99 or BHD 1.099 depending
  on the adjacent currency column; reading the database requires knowing the exponent.
  Misreading raw values during ops work is a real footgun; a display view can join the exponent,
  but the stored number stays unscaled.
- **The exponent is implicit.** A `long` carries no scale, so a client that computes JPY amounts
  with a ×100 factor sends a value 100× too large and the server cannot detect it — the number
  is well-formed. Mitigations: money never travels as a bare number (always `{amount, currency}`),
  and the convention is stated prominently in the OpenAPI description; but this class of client
  bug is inherent to the representation.
- **JSON interop ceiling.** RFC 8259 notes that JSON numbers that are integers in
  [−(2^53)+1, (2^53)−1] are interoperable — implementations agree exactly on their values —
  because consumers commonly parse into IEEE-754 doubles [7]; JavaScript's
  `Number.MAX_SAFE_INTEGER` is the same bound: 9,007,199,254,740,991 [8].
  In currency terms the ceiling is ≈ $90 trillion for 2-exponent currencies, ≈ 9 trillion BHD
  for 3-exponent, ≈ ¥9 quadrillion for JPY — far beyond any v1 use, so plain JSON integers are
  acceptable. Documented escape hatch: if amounts ever approach that range, add a string-encoded
  amount representation via content negotiation; the domain and storage layers are unaffected.
- **Rounding and allocation are deliberately out of scope.** v1 has no operation that divides
  money, so no rounding policy exists to get wrong. The moment fee-splitting or FX conversion
  arrives, integer division loses remainders, and allocation must use explicit largest-remainder
  distribution (every minor unit assigned exactly once) — that is a future ADR, and this ADR
  makes the constraint visible now so it is not improvised later.
- Fixed 64-bit width means no arbitrary precision, and checked arithmetic must be habitual:
  overflow is unreachable for realistic balances but not for arithmetic in general (summing
  adversarial inputs, future multiplication in fee logic, `-Long.MIN_VALUE`). Hence
  `Math.addExact`/`negateExact` rather than `+`/`-`, enforced by the property suite.

### Proof

Automated tests that enforce this decision (invariant IDs from the
[guarantee table](../../README.md#the-guarantees)):

- **I1 — entries balance exactly to zero per currency**: domain unit tests and integration tests
  assert `SUM(amount) = 0` per currency via exact integer equality (`== 0L`, never a tolerance);
  the reconciliation suite re-verifies the same sum in SQL over `BIGINT`.
- **I2 — every entry has ≥ 2 postings with nonzero amounts**: domain validation tests plus the
  `CHECK (amount <> 0)` column constraint exercised by an integration test.
- **`Money` arithmetic property suite** (in-repo harness,
  [ADR-0005](ADR-0005-property-testing-tooling.md)): for arbitrary in-range amounts —
  commutativity (`a + b == b + a`), associativity (`(a + b) + c == a + (b + c)`),
  sign inversion (`a + (−a) == zero`), currency-mismatch rejection, and overflow rejection
  (amounts near `Long.MAX_VALUE`/`Long.MIN_VALUE` throw `ArithmeticException`, never wrap).
- **I14 — ArchUnit rule**: no field, parameter, return type, or local of `float`/`double`
  anywhere in `..domain..` or `..application..`; the build fails on violation.

## Pros and cons of the options

### A. Integer minor units (long / BIGINT) — chosen

- Pros: exact closed arithmetic for everything v1 does; identical value in domain, DB, and JSON;
  native-speed SQL aggregation; industry-standard wire convention [3][4]; overflow detectable
  and testable; zero-sum check is trivial in every layer.
- Cons: raw values unreadable without the exponent; scale implicit (wrong-exponent client bugs
  are undetectable server-side); JSON integer ceiling at 2^53 − 1 [7]; no arbitrary precision.
- Failure modes: a boundary component constructing amounts with the wrong exponent; use of
  unchecked `+` allowing silent wraparound (mitigated: `Money` is the only arithmetic surface,
  and it uses exact methods); a JS client corrupting amounts above 2^53 − 1 (out of realistic
  range, documented).

### B. BigDecimal + NUMERIC(19,4)

- Pros: arbitrary precision — no overflow ceiling at all; database values are human-readable
  (`12.50` means €12.50, no exponent lookup); familiar to every Java reviewer; already carries
  the machinery (`MathContext`, rounding modes) that division-heavy features would need later.
- Cons: `equals` is scale-sensitive — `2.0` and `2.00` are `compareTo`-equal but not
  `equals`-equal [9], which breaks `HashSet`/map keys and naive assertions unless every value is
  scale-normalized everywhere. Operations without an explicit rounding mode throw
  `ArithmeticException` on non-terminating results [9], so rounding discipline is needed on
  every call site even though v1 never divides. A fixed scale of 4 is simultaneously wasteful
  for JPY (exponent 0) and wrong as a validator for everything: `NUMERIC(19,4)` happily stores
  0.005 EUR or 0.5 JPY, so per-currency scale validation must be built *on top of* the
  representation — at which point you have re-implemented the minor-unit exponent while keeping
  none of the integer simplicity. Aggregation over `NUMERIC` is markedly slower than `BIGINT` [5].
  None of BigDecimal's extra power is used by a ledger that only adds and compares.
- Failure modes: scale drift through operation chains (`setScale`/`stripTrailingZeros`
  sprinkled defensively); a forgotten rounding mode throwing in production on the first
  unexpected division; scale-sensitive equality causing false negatives in deduplication or
  test assertions.

### C. Decimal strings end-to-end (store NUMERIC, transmit "12.50")

- Pros: the most human-friendly API — `"12.50"` needs no explanation; immune to the 2^53
  integer ceiling since strings never pass through doubles; self-documenting in logs.
- Cons: pushes parsing, canonicalization, and per-currency scale validation to every boundary:
  is `"12.5"` equal to `"12.50"`? Is `"1.25e1"` acceptable? Locale-formatted `"12,50"`? Each
  edge (API in, API out, DB in, DB out, logs) needs the same canonical grammar or values
  diverge. Storage falls back to `NUMERIC` (inheriting Option B's costs) or text (no SQL
  arithmetic at all — reconciliation would parse every row). Concretely for this system:
  idempotency uses a SHA-256 hash of the canonical request body ([ADR-0004](ADR-0004-idempotency.md)),
  so `"12.50"` vs `"12.5"` — the same money — would hash as different payloads and turn a
  legitimate replay into a 422 conflict unless canonicalization is perfect.
- Failure modes: two clients sending different-but-equal string forms breaking idempotency
  replay; a lenient parser accepting more precision than the currency allows; locale-dependent
  formatting corrupting amounts.

### D. IEEE-754 double — banned

- Pros: none that matter here (hardware-fast, universally available — irrelevant when the
  results are wrong).
- Cons: binary floating point cannot represent most decimal fractions; `0.1 + 0.2` evaluates to
  `0.30000000000000004`, an error of ~5.6 × 10^-17 on a single addition. A ledger built on
  doubles must compare balances with an epsilon, and every epsilon is wrong in one of two ways:
  too small and legitimate entries are rejected as unbalanced, too large and genuinely
  unbalanced entries are accepted. Error accumulates over millions of postings, so the
  reconciliation job could never distinguish drift-by-bug from drift-by-representation.
- Failure modes: this entire option is a failure mode. It is included because "amounts as
  doubles" remains the most common real-world money bug, and the ArchUnit rule (I14) exists so
  the mistake cannot enter this codebase even accidentally — including via a well-meaning
  intermediate calculation in a service class.

## References

1. SIX Group — ISO 4217 currency code maintenance agency (official registry):
   <https://www.six-group.com/en/products-services/financial-information/data-standards.html>
2. ISO 4217 current currency list (List One, XML) — minor units: JPY = 0, EUR/USD = 2, BHD = 3:
   <https://www.six-group.com/dam/download/financial-information/data-center/iso-currrency/lists/list-one.xml>
3. Stripe documentation — Currencies (amounts in the currency's smallest unit; zero-decimal and
   three-decimal handling): <https://docs.stripe.com/currencies>
4. Square documentation — Working with monetary amounts (integer `amount` + ISO 4217
   `currency_code`): <https://developer.squareup.com/docs/build-basics/working-with-monetary-amounts>
5. PostgreSQL 17 documentation — §8.1 Numeric Types (`bigint` range; `numeric` recommended for
   exactness but "very slow compared to the integer types"):
   <https://www.postgresql.org/docs/17/datatype-numeric.html>
6. Java SE 21 API — `java.lang.Math` (`addExact`, `negateExact`, `multiplyExact` throw
   `ArithmeticException` on long overflow):
   <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html>
7. RFC 8259 — The JSON Data Interchange Format, §6 Numbers (integers in
   [−(2^53)+1, (2^53)−1] are interoperable): <https://datatracker.ietf.org/doc/html/rfc8259>
8. MDN — `Number.MAX_SAFE_INTEGER` (2^53 − 1 = 9,007,199,254,740,991):
   <https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Number/MAX_SAFE_INTEGER>
9. Java SE 21 API — `java.math.BigDecimal` (equality is value-and-representation sensitive;
   rounding mode required when exact results are unrepresentable):
   <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html>

Internal: [`Money`](../../src/main/java/io/github/essandhu/ledger/domain/model/Money.java) (the
domain representation) ·
[`AccountType.direction()`](../../src/main/java/io/github/essandhu/ledger/domain/model/AccountType.java)
(sign convention) ·
[V3__journal.sql](../../src/main/resources/db/migration/V3__journal.sql) (`posting.amount` and
`account_balance.balance` as `BIGINT`) · [README §API](../../README.md#api) (money on the wire) ·
[guarantee table](../../README.md#the-guarantees) (invariants I1, I2, I14).
