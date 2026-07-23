package io.github.essandhu.ledger.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.error.InvalidEntryInput;
import io.github.essandhu.ledger.domain.error.TooFewPostings;
import io.github.essandhu.ledger.domain.error.UnbalancedEntry;
import io.github.essandhu.ledger.domain.error.ZeroAmountPosting;

/**
 * A validated posting intent: what the caller asked to record, before it exists. Construction
 * IS validation — an EntryDraft that exists satisfies I1 and I2, with no I/O and no account
 * resolution: at least two legs, every leg nonzero, and every currency netting to exactly zero.
 * Multi-currency drafts are legal precisely because balance is judged per currency, never
 * across — no exchange rates anywhere (PLAN §1, §4.2).
 *
 * <p>Validation ORDER is contract, not accident: nulls → leg count ({@link TooFewPostings}) →
 * per-leg nonzero ({@link ZeroAmountPosting}) → description well-formedness
 * ({@link InvalidEntryInput}) → per-currency zero-sum ({@link UnbalancedEntry} with the nonzero
 * residuals). The earliest broken rule wins, so the problem response and the
 * {@code ledger.posting.rejected} reason tag are deterministic for any given payload. The sum
 * is accumulated with {@link Math#addExact}: overflow mid-sum surfaces as
 * {@link AmountOverflow}, never as a wrapped total that happens to look balanced (ADR-0001).
 *
 * <p>The per-leg zero check lives here, in its pinned place in the order, and not in
 * {@link Leg}'s constructor — a Leg is a dumb pair; the draft owns the rules and their
 * sequencing. The description is nullable (absence means absence) and entry-sized: the account
 * name's text rules but 500 characters. Everything account-dependent — existence, status,
 * currency-vs-account match, overdraft — happens later, under the balance lock (ADR-0003);
 * failing fast here means a garbage payload never touches the database.
 */
public record EntryDraft(String description, List<Leg> legs) {

    public EntryDraft {
        Objects.requireNonNull(legs, "legs");
        legs = List.copyOf(legs); // defensive, immutable; rejects null elements
        if (legs.size() < 2) {
            throw new TooFewPostings(legs.size());
        }
        for (Leg leg : legs) {
            if (leg.amount().isZero()) {
                throw new ZeroAmountPosting(leg.accountId());
            }
        }
        requireValidDescription(description);
        Map<CurrencyCode, Long> residuals = new LinkedHashMap<>();
        for (Leg leg : legs) {
            Money amount = leg.amount();
            long running = residuals.getOrDefault(amount.currency(), 0L);
            try {
                residuals.put(amount.currency(), Math.addExact(running, amount.amount()));
            } catch (ArithmeticException overflow) {
                throw new AmountOverflow(
                        "entry amounts overflow 64-bit minor units while summing %s (ADR-0001: checked arithmetic rejects, never wraps)"
                                .formatted(amount.currency().value()));
            }
        }
        residuals.values().removeIf(sum -> sum == 0);
        if (!residuals.isEmpty()) {
            throw new UnbalancedEntry(residuals);
        }
    }

    private static void requireValidDescription(String description) {
        if (description == null) {
            return; // nullable by design: absence means absence, not empty string
        }
        if (description.isBlank()) {
            throw new InvalidEntryInput("entry description must not be blank when present");
        }
        if (description.length() > 500) {
            throw new InvalidEntryInput("entry description must be at most 500 characters");
        }
        // U+0000 is legal JSON but not storable in PostgreSQL text — without this guard it
        // would surface as a 500 at the JDBC boundary instead of a 400 here. The other control
        // characters are storable but never legitimate in a description.
        if (description.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidEntryInput("entry description must not contain control characters");
        }
    }

    /**
     * One signed movement of the draft: an account plus a debit-positive amount in minor units
     * (PLAN §4.2). The account is an unresolved id on purpose — draft validation is pure and
     * needs no repository; whether the account exists, is ACTIVE, and matches the currency is
     * decided later, under the lock.
     */
    public record Leg(AccountId accountId, Money amount) {

        public Leg {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
