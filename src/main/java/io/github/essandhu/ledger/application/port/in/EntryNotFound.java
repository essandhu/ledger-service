package io.github.essandhu.ledger.application.port.in;

import io.github.essandhu.ledger.domain.model.EntryId;

/**
 * A path-addressed journal entry that does not exist — part of the use-case contract, not a
 * domain rule violation, hence this package and its 404 mapping (the exact mirror of
 * {@link AccountNotFound}). The reversal use case throws it too: the original entry's id is a
 * path segment ({@code POST /journal-entries/{id}/reversal}), so a miss is a 404, not a 422
 * (PLAN §5). The 422 sibling is the payload side — {@code UnknownPostingAccount} for account
 * ids inside an entry body; do not fold the two together.
 */
public class EntryNotFound extends RuntimeException {

    public EntryNotFound(EntryId id) {
        super("no journal entry " + id.value());
    }
}
