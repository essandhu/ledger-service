package io.github.essandhu.ledger.config;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.adapter.web.ProblemTypes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-vocabulary contract (PLAN §8, pinned by the ProblemTypes javadoc): the {@code reason}
 * tag on {@code ledger.posting.rejected} IS the problem-type slug, so a dashboard series and a
 * client's problem document dereference the same name. The decorator hand-duplicates those
 * strings in its REASONS map — this test is what stops the duplication from drifting: it
 * derives the slug set from the ProblemTypes posting-family constants themselves (slug = last
 * path segment of the pinned URI) and asserts the decorator's reason vocabulary matches 1:1.
 * Lives in the config package deliberately: MeteredPostingUseCases is package-private, and the
 * proof needs to read its map, not weaken its encapsulation.
 */
@DisplayName("PLAN §8: the rejected-counter reason tags ARE the ProblemTypes posting slugs — one vocabulary")
class MeteredPostingUseCasesTest {

    /**
     * The exact posting-rejection family (PLAN §5): every 422 the three money-moving ports can
     * raise, each with its pinned type URI. ACCOUNT_CLOSED is the M1 constant that joined the
     * family unchanged; ACCOUNT_BALANCE_NOT_ZERO is deliberately NOT here — see below.
     */
    private static final Set<URI> POSTING_FAMILY = Set.of(
            ProblemTypes.UNBALANCED_ENTRY,
            ProblemTypes.TOO_FEW_POSTINGS,
            ProblemTypes.ZERO_AMOUNT_POSTING,
            ProblemTypes.CURRENCY_MISMATCH,
            ProblemTypes.AMOUNT_OVERFLOW,
            ProblemTypes.OVERDRAFT,
            ProblemTypes.ACCOUNT_FROZEN,
            ProblemTypes.ACCOUNT_CLOSED,
            ProblemTypes.UNKNOWN_ACCOUNT,
            ProblemTypes.ENTRY_ALREADY_REVERSED);

    private static String slug(URI type) {
        String path = type.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("reason tags = posting-family slugs, 1:1 — no drift between metrics and problems")
    void reason_tags_match_the_posting_family_slugs_one_to_one() {
        Set<String> slugs = POSTING_FAMILY.stream()
                .map(MeteredPostingUseCasesTest::slug)
                .collect(Collectors.toSet());
        assertThat(slugs).as("the family has ten distinct slugs").hasSize(10);

        // 1:1 both ways: same size (no duplicate reason strings hiding behind Set.equals)
        // and the same set of names.
        assertThat(MeteredPostingUseCases.REASONS)
                .as("one reason per rejection class, one class per reason")
                .hasSize(slugs.size());
        assertThat(Set.copyOf(MeteredPostingUseCases.REASONS.values()))
                .as("every reason tag is a posting-family slug and every slug is a reason tag")
                .isEqualTo(slugs);
        assertThat(MeteredPostingUseCases.REASONS.values())
                .as("no two classes share a reason string")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("account-balance-not-zero is deliberately absent — it belongs to the close use case, not the posting ports")
    void close_rejection_is_deliberately_absent_from_the_posting_vocabulary() {
        // The application-phase decision (MeteredPostingUseCases javadoc): the decorator wraps
        // only the three money-moving ports; AccountBalanceNotZero is raised by the CLOSE use
        // case (AccountService), which these ports do not carry — counting it here would file a
        // lifecycle rejection under posting metrics and skew both series.
        assertThat(MeteredPostingUseCases.REASONS.values())
                .doesNotContain(slug(ProblemTypes.ACCOUNT_BALANCE_NOT_ZERO));
    }

    @Test
    @DisplayName("M4: idempotency-key-conflict is deliberately absent too — conflicts have their own counter (PLAN §8)")
    void idempotency_conflict_is_deliberately_absent_from_the_posting_vocabulary() {
        // Same exclusion posture as the close rejection: a key conflict is not a posting
        // rejection — nothing was validated, locked, or judged. PLAN §8 gives it the
        // dedicated ledger.idempotency.conflict counter; adding the slug (and the class) to
        // REASONS would double-count every conflict across two series.
        assertThat(MeteredPostingUseCases.REASONS.values())
                .doesNotContain(slug(ProblemTypes.IDEMPOTENCY_KEY_CONFLICT));
        assertThat(MeteredPostingUseCases.REASONS.keySet())
                .doesNotContain(io.github.essandhu.ledger.application.port.in
                        .IdempotencyKeyConflict.class);
    }
}
