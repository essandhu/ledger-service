package io.github.essandhu.ledger.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.domain.error.TooFewPostings;
import io.github.essandhu.ledger.domain.error.UnbalancedEntry;
import io.github.essandhu.ledger.domain.error.ZeroAmountPosting;
import io.github.essandhu.ledger.support.property.Gen;
import io.github.essandhu.ledger.support.property.Gens;
import io.github.essandhu.ledger.support.property.Property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I1 and I2 universally quantified over generated drafts (in-repo harness, ADR-0005; example
 * pins live in {@code EntryDraftTest}). The two I1 properties are a matched pair: every
 * generated balanced draft is accepted AND every perturbed draft is rejected — acceptance alone
 * would pass on a validator that accepts everything, rejection alone on one that rejects
 * everything; only together do they pin "sums to exactly zero per currency". Rejection asserts
 * the exact integer residual, delta ±1 drawn prominently: one minor unit off must reject,
 * because an epsilon in a ledger is an admission of imbalance (ADR-0001). JPY (exponent 0) and
 * BHD (exponent 3) flow through every generator by construction, so no
 * hidden two-decimal assumption can survive these runs.
 */
@Tag("property")
@DisplayName("I1/I2 (property): generated drafts prove exact zero-sum acceptance and rejection")
class EntryDraftPropertyTest {

    /** Raw draft ingredients — construction itself is the assertion under test. */
    private record Ingredients(String description, List<EntryDraft.Leg> legs) {
    }

    /** A balanced leg list with one zero-amount leg injected at a known account. */
    private record Poisoned(List<EntryDraft.Leg> legs, AccountId zeroAccount) {
    }

    @Test
    @DisplayName("I1: every generated balanced draft is accepted, legs preserved in order")
    void balanced_drafts_are_always_accepted() {
        Property.check(balancedIngredients(), ingredients -> {
            EntryDraft draft = new EntryDraft(ingredients.description(), ingredients.legs());
            assertThat(draft.legs()).containsExactlyElementsOf(ingredients.legs());
        });
    }

    @Test
    @DisplayName("I1: every perturbed draft is rejected as UnbalancedEntry with the exact integer residual — no epsilon")
    void perturbed_drafts_are_always_rejected() {
        // The perturbation broke exactly one currency's sum by exactly delta, so the reported
        // residual map must be exactly {currency: delta} — asserting the full map also proves
        // still-balanced currencies are not spuriously reported.
        Property.check(Gens.unbalancedDrafts(), unbalanced ->
                assertThatThrownBy(() -> new EntryDraft(unbalanced.description(), unbalanced.legs()))
                        .isInstanceOf(UnbalancedEntry.class)
                        .hasFieldOrPropertyWithValue("residuals",
                                Map.of(unbalanced.currency(), unbalanced.delta())));
    }

    @Test
    @DisplayName("I2: drafts with fewer than two legs are always rejected")
    void short_drafts_are_always_rejected() {
        Property.check(shortLegLists(), legs ->
                assertThatThrownBy(() -> new EntryDraft(null, legs))
                        .isInstanceOf(TooFewPostings.class));
    }

    @Test
    @DisplayName("I2: a zero-amount leg anywhere in an otherwise balanced draft is always rejected, naming its account")
    void zero_amount_legs_are_always_rejected() {
        Property.check(zeroPoisoned(), poisoned ->
                assertThatThrownBy(() -> new EntryDraft(null, poisoned.legs()))
                        .isInstanceOf(ZeroAmountPosting.class)
                        .hasFieldOrPropertyWithValue("accountId", poisoned.zeroAccount()));
    }

    private static Gen<Ingredients> balancedIngredients() {
        return Gens.descriptions().flatMap(description ->
                Gens.balancedLegs().map(legs -> new Ingredients(description, legs)));
    }

    private static Gen<List<EntryDraft.Leg>> shortLegLists() {
        Gen<List<EntryDraft.Leg>> empty = Gen.constant(List.of());
        // The single leg is kept nonzero so the leg-count rule is the only rule broken — the
        // property must prove I2's count check, not ride on the zero-amount check.
        Gen<List<EntryDraft.Leg>> single = Gens.accountIds().flatMap(accountId ->
                Gens.money().map(money -> List.of(new EntryDraft.Leg(
                        accountId,
                        money.isZero() ? Money.of(1, money.currency()) : money))));
        return Gen.oneOf(empty, single);
    }

    private static Gen<Poisoned> zeroPoisoned() {
        // Adding a zero leg leaves every per-currency sum untouched, so the draft stays
        // balanced and the zero-amount rule is the only rule the poisoned draft can trip
        // (validation order pinned in EntryDraftTest: zero legs are checked before zero-sum).
        return Gens.balancedLegs().flatMap(balanced ->
                Gens.accountIds().flatMap(zeroAccount ->
                        Gens.currencies().flatMap(currency ->
                                Gen.ints(0, balanced.size()).map(index -> {
                                    List<EntryDraft.Leg> poisoned = new ArrayList<>(balanced);
                                    poisoned.add(index, new EntryDraft.Leg(
                                            zeroAccount, Money.of(0, currency)));
                                    return new Poisoned(List.copyOf(poisoned), zeroAccount);
                                }))));
    }
}
