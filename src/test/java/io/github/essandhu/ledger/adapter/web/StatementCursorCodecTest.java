package io.github.essandhu.ledger.adapter.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.PostingId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The strict half of the cursor contract (pinned at M3): only the codec's own canonical
 * output decodes — lenient UUID/Instant spellings, padding variants, off-grid instants, and
 * foreign-account tokens are all InvalidCursor (→ 400). The roundtrip law itself is
 * StatementCursorCodecPropertyTest.
 */
@DisplayName("Statement cursor codec (M3): strict, canonical, account-bound")
class StatementCursorCodecTest {

    private static final AccountId ACCOUNT =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-0000000000aa"));
    private static final AccountId OTHER =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-0000000000bb"));
    private static final StatementCursor CURSOR = new StatementCursor(
            Instant.parse("2026-07-22T10:00:00.000123Z"),
            new PostingId(UUID.fromString("019817b5-0000-7000-8000-0000000000f1")));

    private static String b64(String token) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a cursor decodes only against the account it was issued for")
    void rejects_a_foreign_accounts_cursor() {
        String token = StatementCursorCodec.encode(ACCOUNT, CURSOR);

        assertThat(StatementCursorCodec.decode(ACCOUNT, token)).isEqualTo(CURSOR);
        assertThatThrownBy(() -> StatementCursorCodec.decode(OTHER, token))
                .isInstanceOf(InvalidCursor.class)
                .hasMessageContaining("different account");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("non-canonical or malformed tokens are rejected outright")
    @ValueSource(strings = {
            "%%%not-base64%%%",
            "", // empty parameter
            // too few / too many segments
            "019817b4-0000-7000-8000-0000000000aa/2026-07-22T10:00:00Z",
            "019817b4-0000-7000-8000-0000000000aa/2026-07-22T10:00:00Z/019817b5-0000-7000-8000-0000000000f1/extra",
            // UUID.fromString would ACCEPT this lenient spelling — the canonical check must not
            "1-1-1-1-1/2026-07-22T10:00:00Z/019817b5-0000-7000-8000-0000000000f1",
            // uppercase hex parses, then re-encodes lowercase — not our token
            "019817B4-0000-7000-8000-0000000000AA/2026-07-22T10:00:00Z/019817b5-0000-7000-8000-0000000000f1",
            // Instant.parse accepts the offset form, then normalizes to Z — not our token
            "019817b4-0000-7000-8000-0000000000aa/2026-07-22T12:00:00+02:00/019817b5-0000-7000-8000-0000000000f1",
            // canonical spelling but FINER than the database's microsecond grid
            "019817b4-0000-7000-8000-0000000000aa/2026-07-22T10:00:00.000000500Z/019817b5-0000-7000-8000-0000000000f1",
            // canonical spelling but OUTSIDE the range timestamptz can bind (500-proofing)
            "019817b4-0000-7000-8000-0000000000aa/+1000000-01-01T00:00:00Z/019817b5-0000-7000-8000-0000000000f1",
            "019817b4-0000-7000-8000-0000000000aa/-1000000-01-01T00:00:00Z/019817b5-0000-7000-8000-0000000000f1",
            // not an instant / not a uuid at all
            "019817b4-0000-7000-8000-0000000000aa/yesterday/019817b5-0000-7000-8000-0000000000f1",
            "not-a-uuid/2026-07-22T10:00:00Z/also-not-a-uuid",
    })
    void rejects_tokens_this_service_never_issued(String rawToken) {
        String token = rawToken.startsWith("%") || rawToken.isEmpty() ? rawToken : b64(rawToken);

        assertThatThrownBy(() -> StatementCursorCodec.decode(ACCOUNT, token))
                .isInstanceOf(InvalidCursor.class);
    }

    @Test
    @DisplayName("a padded base64 variant of a genuine token is still not OUR token")
    void rejects_padded_variant_of_a_genuine_token() {
        String canonical = StatementCursorCodec.encode(ACCOUNT, CURSOR);
        String padded = Base64.getUrlEncoder() // WITH padding
                .encodeToString(Base64.getUrlDecoder().decode(canonical));
        // Only meaningful if the padded form differs; for this fixture it does.
        assertThat(padded).isNotEqualTo(canonical);

        assertThatThrownBy(() -> StatementCursorCodec.decode(ACCOUNT, padded))
                .isInstanceOf(InvalidCursor.class);
    }
}
