package io.github.essandhu.ledger.adapter.web;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.PostingId;

/**
 * The opaque statement cursor (PLAN §5, pinned at M3): base64url, no padding, over
 * {@code "accountId/postedAt/postingId"}. The token embeds the ACCOUNT it was issued for, and
 * {@link #decode} rejects it against any other account — a pasted foreign cursor would
 * otherwise produce a well-formed but silently truncated statement, exactly the
 * silent-wrong-money failure class this project exists to rule out.
 *
 * <p>Decoding is STRICT: only this codec's own canonical output is accepted (re-encoding the
 * decoded fields must reproduce the token byte for byte), so the lenient parsers underneath
 * ({@code UUID.fromString} accepts short hex groups, {@code Instant.parse} accepts offset
 * forms) cannot smuggle in non-canonical spellings — and a position finer than the database's
 * microsecond grid is refused outright rather than left to driver rounding to place.
 */
final class StatementCursorCodec {

    private StatementCursorCodec() {
    }

    static String encode(AccountId accountId, StatementCursor cursor) {
        String token = accountId.value() + "/" + cursor.postedAt() + "/" + cursor.id().value();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws InvalidCursor if the token is not canonical codec output, is off the microsecond
     *         grid, or was issued for a different account
     */
    static StatementCursor decode(AccountId accountId, String cursor) {
        String token;
        try {
            token = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw notIssuedHere();
        }
        String[] parts = token.split("/", -1);
        if (parts.length != 3) {
            throw notIssuedHere();
        }
        AccountId issuedFor;
        StatementCursor decoded;
        try {
            issuedFor = new AccountId(UUID.fromString(parts[0]));
            decoded = new StatementCursor(Instant.parse(parts[1]),
                    new PostingId(UUID.fromString(parts[2])));
        } catch (IllegalArgumentException | DateTimeException unparseable) {
            throw notIssuedHere();
        }
        if (!encode(issuedFor, decoded).equals(cursor)) {
            throw notIssuedHere(); // parseable but not canonical — we never issued it
        }
        if (!decoded.postedAt().equals(decoded.postedAt().truncatedTo(ChronoUnit.MICROS))) {
            throw notIssuedHere(); // finer than timestamptz's grid — we never issued it
        }
        if (decoded.postedAt().isBefore(WireInstants.MIN)
                || decoded.postedAt().isAfter(WireInstants.MAX)) {
            throw notIssuedHere(); // outside the range timestamptz can even bind (never a 500)
        }
        if (!issuedFor.equals(accountId)) {
            throw new InvalidCursor(
                    "cursor was issued for a different account's statement");
        }
        return decoded;
    }

    private static InvalidCursor notIssuedHere() {
        return new InvalidCursor("cursor is not a token this service issued");
    }
}
