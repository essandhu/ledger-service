package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

import io.github.essandhu.ledger.application.port.out.IdempotencyRecord;
import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Money;
import io.github.essandhu.ledger.domain.model.Posting;
import io.github.essandhu.ledger.domain.model.PostingId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring Data {@link Persistable} contract, pinned per entity. Load-bearing, not ceremony:
 * {@code save()} routes on {@code isNew()} — true persists, false merge-probes with a SELECT —
 * and {@code getId()} is the key every exists/merge decision uses, so a face that returned a
 * NEIGHBORING id column (posting → entry_id, finding → run_id) would silently hand Spring Data
 * the wrong identity and corrupt entity state transitions. Entities carrying several UUID
 * columns are therefore constructed with DISTINCT ids, making a wrong-field {@code getId}
 * unable to pass. {@code isNew()} is asserted against each entity's documented lifecycle: the
 * only instances the adapters ever save are freshly constructed, so the answer is always true
 * — the declaration exists purely to spare the hottest write paths a probing SELECT. Same
 * package as the entities, so the package-private construction paths are the ones under test;
 * no Spring, no database — this is the entities' own Java contract.
 */
@DisplayName("Persistable faces: getId() is exactly the constructed identifier, isNew() the declared lifecycle fact")
class PersistableContractTest {

    private static final Instant T0 = Instant.parse("2026-07-25T07:00:00Z");
    private static final Instant T1 = T0.plusSeconds(3600);
    private static final CurrencyCode EUR = new CurrencyCode("EUR");

    @Test
    @DisplayName("AccountBalanceJpaEntity reports the account id its zero seed was given")
    void account_balance_face_reports_the_account_id() {
        AccountId accountId = new AccountId(UUID.randomUUID());

        AccountBalanceJpaEntity entity = AccountBalanceJpaEntity.zero(accountId, T0);

        assertThat(entity.getId()).isEqualTo(accountId.value());
        assertThat(entity.isNew())
                .as("only fresh zero seeds are ever saved — merge would cost a probing SELECT"
                        + " per account creation")
                .isTrue();
    }

    @Test
    @DisplayName("PostingJpaEntity reports the posting id — never its entry or account column")
    void posting_face_reports_the_posting_id() {
        PostingId postingId = new PostingId(UUID.randomUUID());
        EntryId entryId = new EntryId(UUID.randomUUID());
        AccountId accountId = new AccountId(UUID.randomUUID());

        PostingJpaEntity entity = PostingJpaEntity.fromDomain(
                new Posting(postingId, entryId, accountId, Money.of(700, EUR), T0));

        assertThat(entity.getId()).isEqualTo(postingId.value());
        assertThat(entity.isNew())
                .as("append-only: every fromDomain instance is a fresh row")
                .isTrue();
    }

    @Test
    @DisplayName("JournalEntryJpaEntity reports the entry id — never reversal_of, its other UUID column")
    void journal_entry_face_reports_the_entry_id() {
        EntryId entryId = new EntryId(UUID.randomUUID());
        EntryId reversalOf = new EntryId(UUID.randomUUID());
        // A REVERSAL header, so BOTH UUID columns are populated and distinct — the shape under
        // which a wrong-field getId cannot hide.
        JournalEntry reversal = new JournalEntry(entryId, EntryType.REVERSAL, null, reversalOf,
                "persistable-subject", "persistable-key", T0, List.of());

        JournalEntryJpaEntity entity = JournalEntryJpaEntity.fromDomain(reversal);

        assertThat(entity.getId()).isEqualTo(entryId.value());
        assertThat(entity.isNew())
                .as("append-only: every fromDomain instance is a fresh row")
                .isTrue();
    }

    @Test
    @DisplayName("IdempotencyRecordJpaEntity reports the composite (createdBy, idemKey) scope as its id")
    void idempotency_record_face_reports_the_composite_scope_key() {
        IdempotencyRecord record = new IdempotencyRecord("persistable-alice", "persistable-key-1",
                "a".repeat(64), new EntryId(UUID.randomUUID()), 201, "{}", T0, T1);

        IdempotencyRecordJpaEntity entity = IdempotencyRecordJpaEntity.fromRecord(record);

        // Record equality from the language (the Key javadoc's point): the id IS the
        // (principal, key) scope, in that component order.
        assertThat(entity.getId()).isEqualTo(
                new IdempotencyRecordJpaEntity.Key("persistable-alice", "persistable-key-1"));
        assertThat(entity.isNew())
                .as("write-once rows: every fromRecord instance is a fresh row")
                .isTrue();
    }

    @Test
    @DisplayName("ReconciliationRunJpaEntity reports the run id its RUNNING seed was given")
    void reconciliation_run_face_reports_the_run_id() {
        UUID runId = UUID.randomUUID();

        ReconciliationRunJpaEntity entity =
                ReconciliationRunJpaEntity.running(runId, T0, "persistable-trigger");

        assertThat(entity.getId()).isEqualTo(runId);
        assertThat(entity.isNew())
                .as("only fresh RUNNING seeds are ever saved — the terminal transition is a"
                        + " set-based UPDATE, never a managed instance")
                .isTrue();
    }

    @Test
    @DisplayName("ReconciliationFindingJpaEntity reports the finding id — never its run or account column")
    void reconciliation_finding_face_reports_the_finding_id() {
        UUID findingId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AccountId accountId = new AccountId(UUID.randomUUID());

        ReconciliationFindingJpaEntity entity = ReconciliationFindingJpaEntity.fromDomain(
                new ReconciliationFinding(findingId, runId, accountId, 100, 2, 90, 1, 10));

        assertThat(entity.getId()).isEqualTo(findingId);
        assertThat(entity.isNew())
                .as("write-once audit rows: every fromDomain instance is a fresh row")
                .isTrue();
    }

    @Test
    @DisplayName("AccountJpaEntity stays OFF Persistable — its isNew answer is the null-@Version sentinel")
    void account_entity_does_not_implement_persistable() {
        // The one mutable entity derives newness from @Version being null (its class javadoc);
        // an always-true Persistable face here would misclassify loaded accounts as new.
        assertThat(Persistable.class.isAssignableFrom(AccountJpaEntity.class)).isFalse();
        // Its identifier accessor still honors the same contract the Persistable faces pin:
        // the domain id, verbatim — the account adapter's lookups ride on it.
        AccountId id = new AccountId(UUID.randomUUID());
        AccountJpaEntity entity = AccountJpaEntity.fromDomain(
                Account.open(id, "persistable-probe", EUR, AccountType.ASSET, false, T0));
        assertThat(entity.id()).isEqualTo(id.value());
    }
}
