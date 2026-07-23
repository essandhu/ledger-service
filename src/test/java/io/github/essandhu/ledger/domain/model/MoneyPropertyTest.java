package io.github.essandhu.ledger.domain.model;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.domain.error.CurrencyMismatch;
import io.github.essandhu.ledger.support.property.Gen;
import io.github.essandhu.ledger.support.property.Gens;
import io.github.essandhu.ledger.support.property.Property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0001's Proof list, item by item, over generated values: commutativity, associativity,
 * sign inversion ({@code a + (−a) = zero}), currency-mismatch rejection, and overflow near
 * {@code Long.MAX_VALUE}/{@code MIN_VALUE} throwing {@link ArithmeticException} — never
 * wrapping. {@code MoneyTest} pins each law on examples; this suite universally quantifies them
 * through the in-repo harness (ADR-0005), with JPY (exponent 0) and BHD (exponent 3) always in
 * the currency mix so no law accidentally leans on "two decimal places" (TEST-STRATEGY §2.2).
 *
 * <p>The algebraic laws run over amounts bounded to {@code Long.MAX_VALUE / 4} — "arbitrary
 * in-range amounts" in the ADR's words — because a law about sums needs representable sums;
 * what happens OUTSIDE that range is not exempted but proven separately by the overflow
 * property, over pairs constructed so every sum is guaranteed unrepresentable.
 */
@Tag("property")
@DisplayName("ADR-0001 (property): Money arithmetic laws hold for all generated amounts and currencies")
class MoneyPropertyTest {

    private static final List<CurrencyCode> CURRENCIES = List.of(
            new CurrencyCode("EUR"), new CurrencyCode("USD"),
            new CurrencyCode("JPY"), new CurrencyCode("BHD"));

    /** {@code Long.MAX_VALUE / 4}: any two- or three-term sum stays within 64 bits, so the
     * algebraic laws are never confounded by overflow (which has its own property below). */
    private static final long SAFE = Long.MAX_VALUE / 4;

    private record Pair(Money a, Money b) {
    }

    private record Triple(Money a, Money b, Money c) {
    }

    @Test
    @DisplayName("commutativity: a + b = b + a for all same-currency pairs")
    void addition_is_commutative() {
        Property.check(sameCurrencyPairs(), pair ->
                assertThat(pair.a().plus(pair.b())).isEqualTo(pair.b().plus(pair.a())));
    }

    @Test
    @DisplayName("associativity: (a + b) + c = a + (b + c) for all same-currency triples")
    void addition_is_associative() {
        Property.check(sameCurrencyTriples(), triple ->
                assertThat(triple.a().plus(triple.b()).plus(triple.c()))
                        .isEqualTo(triple.a().plus(triple.b().plus(triple.c()))));
    }

    @Test
    @DisplayName("sign inversion: a + (−a) = zero for every negatable amount — the law reversals depend on")
    void a_plus_its_negation_is_zero() {
        Property.check(negatableMoney(), money ->
                assertThat(money.plus(money.negate())).isEqualTo(Money.zero(money.currency())));
    }

    @Test
    @DisplayName("currency-mismatch rejection: adding across currencies always throws CurrencyMismatch")
    void mixed_currency_addition_is_always_rejected() {
        Property.check(mixedCurrencyPairs(), pair ->
                assertThatThrownBy(() -> pair.a().plus(pair.b()))
                        .isInstanceOf(CurrencyMismatch.class));
    }

    @Test
    @DisplayName("overflow near Long.MAX_VALUE/MIN_VALUE always throws ArithmeticException — never wraps")
    void overflow_always_throws_and_never_wraps() {
        // Every generated pair sums past a 64-bit limit by construction. Throwing IS the
        // never-wraps proof: unchecked addition would return a wrapped long here, not throw
        // (ADR-0001: Math.addExact, loud overflow, no silent wraparound).
        Property.check(overflowingPairs(), pair ->
                assertThatThrownBy(() -> pair.a().plus(pair.b()))
                        .isInstanceOf(ArithmeticException.class));
    }

    private static Gen<Pair> sameCurrencyPairs() {
        return Gens.currencies().flatMap(currency ->
                Gen.longs(-SAFE, SAFE).flatMap(a ->
                        Gen.longs(-SAFE, SAFE).map(b ->
                                new Pair(Money.of(a, currency), Money.of(b, currency)))));
    }

    private static Gen<Triple> sameCurrencyTriples() {
        return Gens.currencies().flatMap(currency ->
                Gen.longs(-SAFE, SAFE).flatMap(a ->
                        Gen.longs(-SAFE, SAFE).flatMap(b ->
                                Gen.longs(-SAFE, SAFE).map(c -> new Triple(
                                        Money.of(a, currency),
                                        Money.of(b, currency),
                                        Money.of(c, currency))))));
    }

    private static Gen<Money> negatableMoney() {
        // Long.MIN_VALUE is the one amount with no 64-bit negation (two's-complement asymmetry);
        // negate() throwing on it is pinned in MoneyTest. Remapping it to Long.MAX_VALUE keeps
        // this law universally quantified over every negatable amount, near-limit bands included.
        return Gens.money().map(money ->
                money.amount() == Long.MIN_VALUE ? Money.of(Long.MAX_VALUE, money.currency()) : money);
    }

    private static Gen<Pair> mixedCurrencyPairs() {
        // Index + nonzero offset modulo the table guarantees the currencies always differ.
        return Gen.ints(0, CURRENCIES.size() - 1).flatMap(first ->
                Gen.ints(1, CURRENCIES.size() - 1).flatMap(offset ->
                        Gens.amounts().flatMap(a ->
                                Gens.amounts().map(b -> new Pair(
                                        Money.of(a, CURRENCIES.get(first)),
                                        Money.of(b, CURRENCIES.get((first + offset) % CURRENCIES.size())))))));
    }

    private static Gen<Pair> overflowingPairs() {
        // a ∈ [MAX − 1024, MAX], b = (MAX − a) + excess with excess ≥ 1 ⇒ a + b = MAX + excess.
        Gen<Pair> pastMax = Gens.currencies().flatMap(currency ->
                Gen.longs(Long.MAX_VALUE - 1_024, Long.MAX_VALUE).flatMap(a ->
                        Gen.longs(1, 1_024).map(excess -> new Pair(
                                Money.of(a, currency),
                                Money.of(Long.MAX_VALUE - a + excess, currency)))));
        // a ∈ [MIN, MIN + 1024], b = (MIN − a) − excess ⇒ a + b = MIN − excess.
        Gen<Pair> pastMin = Gens.currencies().flatMap(currency ->
                Gen.longs(Long.MIN_VALUE, Long.MIN_VALUE + 1_024).flatMap(a ->
                        Gen.longs(1, 1_024).map(excess -> new Pair(
                                Money.of(a, currency),
                                Money.of(Long.MIN_VALUE - a - excess, currency)))));
        return Gen.oneOf(pastMax, pastMin);
    }
}
