package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.support.fakes.FakeReconciliationRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep reader's paging protocol against the fake's contract-enforcing keyset: every
 * account exactly once, in the database's uuid order, across page boundaries — including ids
 * whose first bit is set, where a signed compare would disagree with the database.
 */
@DisplayName("ComparisonPageReader: keyset pages become one item stream, no skips, no repeats")
class ComparisonPageReaderTest {

    private final FakeReconciliationRepository store = new FakeReconciliationRepository();

    private static BalanceComparison clean(String uuid) {
        return new BalanceComparison(
                new AccountId(UUID.fromString(uuid)), 0, 0, 0, 0);
    }

    @Test
    @DisplayName("reads every account exactly once across page boundaries, then null forever")
    void streams_all_pages_in_database_order() {
        // Seeded out of order; two ids with the high bit set (f…, 8…) catch signed-compare
        // drift in any mirror of the database's unsigned uuid order.
        store.seedComparisons(List.of(
                clean("f0000000-0000-7000-8000-000000000005"),
                clean("00000000-0000-7000-8000-000000000001"),
                clean("80000000-0000-7000-8000-000000000004"),
                clean("00000000-0000-7000-8000-000000000002"),
                clean("7fffffff-0000-7000-8000-000000000003")));
        ComparisonPageReader reader = new ComparisonPageReader(store, 2);

        List<String> seen = new ArrayList<>();
        BalanceComparison item;
        while ((item = reader.read()) != null) {
            seen.add(item.accountId().value().toString());
        }
        assertThat(seen).containsExactly(
                "00000000-0000-7000-8000-000000000001",
                "00000000-0000-7000-8000-000000000002",
                "7fffffff-0000-7000-8000-000000000003",
                "80000000-0000-7000-8000-000000000004",
                "f0000000-0000-7000-8000-000000000005");
        assertThat(reader.read()).as("exhausted stays exhausted").isNull();
    }

    @Test
    @DisplayName("a page-size-multiple total ends on one empty page, not an extra account")
    void exact_multiple_of_page_size_terminates() {
        store.seedComparisons(List.of(
                clean("00000000-0000-7000-8000-000000000001"),
                clean("00000000-0000-7000-8000-000000000002")));
        ComparisonPageReader reader = new ComparisonPageReader(store, 2);
        assertThat(reader.read()).isNotNull();
        assertThat(reader.read()).isNotNull();
        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("an empty ledger reads null immediately")
    void empty_scan_is_immediately_exhausted() {
        assertThat(new ComparisonPageReader(store, 2).read()).isNull();
    }
}
