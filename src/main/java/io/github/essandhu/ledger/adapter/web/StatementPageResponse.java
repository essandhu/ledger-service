package io.github.essandhu.ledger.adapter.web;

import java.util.List;

import io.github.essandhu.ledger.application.port.in.StatementPage;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * The statement page envelope (PLAN §5, pinned at M3). {@code nextCursor} is ALWAYS the
 * position to poll next — opaque and account-bound: the last line's position when the page
 * has content, the request's own cursor when it does not (tail-following is stateless: just
 * re-send what the last response handed back), and null only when a cursor-less request found
 * nothing, because resuming from genesis IS "no cursor". Empty {@code content} means "caught
 * up as of this read", never "the stream is closed".
 */
record StatementPageResponse(List<StatementLineResponse> content, String nextCursor) {

    static StatementPageResponse from(AccountId accountId, StatementPage page) {
        return new StatementPageResponse(
                page.lines().stream().map(StatementLineResponse::from).toList(),
                page.next()
                        .map(cursor -> StatementCursorCodec.encode(accountId, cursor))
                        .orElse(null));
    }
}
