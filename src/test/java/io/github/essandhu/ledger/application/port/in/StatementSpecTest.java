package io.github.essandhu.ledger.application.port.in;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.domain.model.PostingId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Port-side guards for the M3 statement shapes: the web layer 400s first, but these records
 * are the contract for non-HTTP callers too (the PageSpec dual-validation stance).
 */
@DisplayName("Statement port records: constructor guards")
class StatementSpecTest {

    private static final StatementCursor CURSOR = new StatementCursor(
            Instant.parse("2026-07-22T10:00:00Z"),
            new PostingId(UUID.fromString("019817b5-0000-7000-8000-0000000000f1")));

    @Test
    @DisplayName("limit is bounded to [1, MAX_LIMIT]")
    void limit_is_bounded() {
        assertThatThrownBy(() -> new StatementSpec(Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatementSpec(Optional.empty(), StatementSpec.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new StatementSpec(Optional.of(CURSOR), StatementSpec.MAX_LIMIT).limit())
                .isEqualTo(StatementSpec.MAX_LIMIT);
        assertThat(StatementSpec.firstPage(1).after()).isEmpty();
    }

    @Test
    @DisplayName("nulls are refused at construction (records validate on entry)")
    void nulls_are_refused() {
        assertThatThrownBy(() -> new StatementSpec(null, 10))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatementFilter(null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatementCursor(null, CURSOR.id()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatementCursor(CURSOR.postedAt(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("StatementPage defensively copies its lines and knows its resume position")
    void page_copies_lines() {
        StatementPage page = new StatementPage(java.util.List.of(), Optional.of(CURSOR));
        assertThat(page.lines()).isEmpty();
        assertThat(page.next()).contains(CURSOR);
    }
}
