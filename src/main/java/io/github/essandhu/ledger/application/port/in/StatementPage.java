package io.github.essandhu.ledger.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.essandhu.ledger.domain.model.Posting;

/**
 * One page of statement lines in {@code (posted_at, id)} ascending order — domain postings
 * verbatim, raw signed amounts (the sign convention owns the client-facing sign story).
 *
 * <p>{@code next} is ALWAYS the position to resume from (pinned at M3): the last line's
 * position when the page has content; the request's own cursor when it does not — so a
 * tail-following client just re-polls what the last response handed it, statelessly. Empty
 * only when a cursor-less request found nothing, because resuming from genesis is "no cursor"
 * again. Empty {@code lines} means "caught up as of this read", never "the stream is closed":
 * postings appended later extend the walk from {@code next}.
 */
public record StatementPage(List<Posting> lines, Optional<StatementCursor> next) {

    public StatementPage {
        lines = List.copyOf(lines);
        Objects.requireNonNull(next, "next");
    }
}
