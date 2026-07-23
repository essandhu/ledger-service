package io.github.essandhu.ledger.domain.model;

/**
 * How a journal entry came to exist. TRANSFER is the two-leg convenience the transfer endpoint
 * posts; JOURNAL is an arbitrary 2..n-leg entry (PLAN §4.1); REVERSAL is an entry whose legs
 * exactly negate an earlier entry's, linked via reversal_of (I11). The type records intent for
 * audit and observability (the {@code entry_type} tag on {@code ledger.posting.duration},
 * PLAN §8) — validation is identical for all three; a REVERSAL differs only in carrying its
 * link, enforced by {@link JournalEntry} and the {@code journal_entry_reversal_shape} CHECK.
 */
public enum EntryType {
    TRANSFER,
    JOURNAL,
    REVERSAL
}
