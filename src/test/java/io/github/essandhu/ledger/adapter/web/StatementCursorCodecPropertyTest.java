package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.PostingId;
import io.github.essandhu.ledger.support.property.Gen;
import io.github.essandhu.ledger.support.property.Property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cursor codec's laws, universally quantified: every position the server can actually
 * issue (any microsecond-grid instant in timestamptz range × any uuid) round-trips exactly,
 * and no token round-trips onto a DIFFERENT account. Generators draw from the rng only
 * (ADR-0005's determinism contract) — no UUID.randomUUID.
 */
@Tag("property")
@DisplayName("Statement cursor codec (M3, property): encode∘decode = identity, account-bound")
class StatementCursorCodecPropertyTest {

    /** Microsecond-grid instants, 1970 through ~2200 — the positions the Clock can mint. */
    private static Gen<Instant> gridInstants() {
        return Gen.longs(0, 7_258_118_400L).flatMap(seconds ->
                Gen.longs(0, 999_999L).map(micros ->
                        Instant.ofEpochSecond(seconds, micros * 1_000)));
    }

    private static Gen<UUID> anyUuid() {
        return Gen.longs(Long.MIN_VALUE, Long.MAX_VALUE).flatMap(msb ->
                Gen.longs(Long.MIN_VALUE, Long.MAX_VALUE).map(lsb -> new UUID(msb, lsb)));
    }

    private record Issued(AccountId account, StatementCursor cursor) {
    }

    private static Gen<Issued> issuedCursors() {
        return anyUuid().flatMap(account ->
                gridInstants().flatMap(postedAt ->
                        anyUuid().map(posting -> new Issued(new AccountId(account),
                                new StatementCursor(postedAt, new PostingId(posting))))));
    }

    @Test
    @DisplayName("decode(encode(cursor)) = cursor for every issuable position")
    void roundtrip_is_identity() {
        Property.check(issuedCursors(), issued ->
                assertThat(StatementCursorCodec.decode(issued.account(),
                        StatementCursorCodec.encode(issued.account(), issued.cursor())))
                        .isEqualTo(issued.cursor()));
    }

    @Test
    @DisplayName("no issued token decodes against a different account")
    void tokens_never_cross_accounts() {
        Gen<UUID> otherAccount = anyUuid();
        Property.check(issuedCursors().flatMap(issued ->
                        otherAccount.map(other -> new Object[] {issued, new AccountId(other)})),
                pair -> {
                    Issued issued = (Issued) pair[0];
                    AccountId other = (AccountId) pair[1];
                    String token = StatementCursorCodec.encode(issued.account(), issued.cursor());
                    if (other.equals(issued.account())) {
                        assertThat(StatementCursorCodec.decode(other, token))
                                .isEqualTo(issued.cursor());
                    } else {
                        assertThatThrownBy(() -> StatementCursorCodec.decode(other, token))
                                .isInstanceOf(InvalidCursor.class);
                    }
                });
    }
}
