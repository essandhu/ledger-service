package io.github.essandhu.ledger.adapter.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface JournalEntryJpaRepository extends JpaRepository<JournalEntryJpaEntity, UUID> {

    /**
     * I11's in-lock existence check: whether any REVERSAL already points at the entry. No
     * {@code entry_type} predicate is needed — the {@code journal_entry_reversal_shape} CHECK
     * guarantees a non-null {@code reversal_of} occurs only on REVERSAL rows. Served by the
     * partial unique index {@code journal_entry_reversed_once}, whose uniqueness is also the
     * at-rest backstop should two racing reversals ever slip past the balance-lock
     * serialization (ADR-0003).
     */
    boolean existsByReversalOf(UUID originalId);
}
