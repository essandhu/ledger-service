package io.github.essandhu.ledger.application.port.in;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The M4 key shape rules at the guard itself (PLAN §5 M4 pin): non-blank, no control
 * characters, no commas, at most 200 chars — each a 400-family violation. The integration
 * suite proves the HTTP posture; this pins the rules one by one so deleting any single check
 * fails a named test rather than surfacing as a downstream 500 off the V4 CHECKs.
 */
@DisplayName("M4: Idempotency-Key shape rules (requireValid)")
class InvalidIdempotencyKeyTest {

    @Test
    @DisplayName("a valid key — up to the 200-char maximum — passes through verbatim")
    void valid_keys_pass_verbatim() {
        assertThat(InvalidIdempotencyKey.requireValid("a")).isEqualTo("a");
        String max = "k".repeat(InvalidIdempotencyKey.MAX_LENGTH);
        assertThat(InvalidIdempotencyKey.requireValid(max)).isSameAs(max);
    }

    @Test
    @DisplayName("blank keys are rejected — whitespace is not identity")
    void blank_keys_are_rejected() {
        for (String blank : new String[] {"", " ", "   "}) {
            assertThatThrownBy(() -> InvalidIdempotencyKey.requireValid(blank))
                    .as("key '%s'".formatted(blank))
                    .isInstanceOf(InvalidIdempotencyKey.class);
        }
    }

    @Test
    @DisplayName("oversized keys are rejected at exactly MAX_LENGTH + 1")
    void oversized_keys_are_rejected() {
        assertThatThrownBy(() -> InvalidIdempotencyKey.requireValid(
                "k".repeat(InvalidIdempotencyKey.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidIdempotencyKey.class);
    }

    @Test
    @DisplayName("control characters are rejected — tab, newline, NUL")
    void control_characters_are_rejected() {
        for (String key : new String[] {"a\tb", "a\nb", "a" + (char) 0 + "b"}) {
            assertThatThrownBy(() -> InvalidIdempotencyKey.requireValid(key))
                    .isInstanceOf(InvalidIdempotencyKey.class);
        }
    }

    @Test
    @DisplayName("commas are rejected — HTTP joins duplicate headers with commas, so a comma key is transport-ambiguous")
    void commas_are_rejected() {
        // The doubled-header shape ("K,K") must fail loudly on FIRST contact: recorded as
        // "K,K", it would miss the replay when an intermediary dedupes the retry to "K" —
        // the silent double-post the key exists to prevent.
        for (String key : new String[] {"K,K", "a,b", ","}) {
            assertThatThrownBy(() -> InvalidIdempotencyKey.requireValid(key))
                    .isInstanceOf(InvalidIdempotencyKey.class);
        }
    }

    @Test
    @DisplayName("a null key is a programming error (NPE), not a client 400 — absence is the web layer's 400")
    void null_key_is_a_programming_error() {
        assertThatThrownBy(() -> InvalidIdempotencyKey.requireValid(null))
                .isInstanceOf(NullPointerException.class);
    }
}
