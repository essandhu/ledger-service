package io.github.essandhu.ledger.config;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.fasterxml.uuid.UUIDClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.domain.model.AccountId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The id-order contract under a hostile wall clock. PostingJpaRepository's "id order IS leg
 * order" reading (I11's positional reversal compare, GET's leg rendering) leans on ids from
 * this generator being strictly increasing in the order they were handed out — and JUG's
 * TimeBasedEpochGenerator alone does NOT survive a backwards wall-clock step (NTP correction,
 * VM resume): its same-millisecond counter only engages on exact timestamp equality, so a
 * smaller timestamp simply mints a smaller id. IdGeneratorConfig makes the contract true by
 * construction with a monotone non-decreasing clock; this test replays the exact failure —
 * a clock stepping backwards mid-sequence — and asserts order survives, in the lock protocol's
 * own canonical bytewise order (the order PostgreSQL sorts uuid columns in).
 */
@DisplayName("IdGeneratorConfig: ids stay strictly increasing across a backwards wall-clock step")
class IdGeneratorConfigTest {

    /** A wall clock scripted to step BACKWARDS mid-sequence — an NTP correction in miniature. */
    private static final class SteppingClock extends UUIDClock {

        private final Iterator<Long> millis;

        SteppingClock(List<Long> steps) {
            this.millis = steps.iterator();
        }

        @Override
        public long currentTimeMillis() {
            return millis.next();
        }
    }

    @Test
    @DisplayName("I11 precondition: sequential ids sort strictly ascending even when the clock steps back")
    void ids_survive_a_backwards_clock_step() {
        long t0 = 1_753_200_000_000L; // fixed epoch millis — deterministic, no ambient time
        SteppingClock wall = new SteppingClock(List.of(
                t0, t0,          // same millisecond — must take the counter path
                t0 - 40_000,     // the clock snaps 40 s backwards
                t0 - 40_000,
                t0 - 1,          // still behind the high-water mark
                t0,              // back at it
                t0 + 1));        // and past it
        IdGenerator ids = IdGeneratorConfig.monotonicUuidV7(wall);

        List<UUID> generated = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            generated.add(ids.nextId());
        }

        for (int i = 1; i < generated.size(); i++) {
            // The comparator IS the lock protocol's canonical order (BalanceRepository):
            // bytewise unsigned, identical to PostgreSQL's uuid ordering — the same total
            // order PostingJpaRepository's findByEntryIdOrderByIdAsc reads back in.
            assertThat(BalanceRepository.CANONICAL_ORDER.compare(
                    new AccountId(generated.get(i - 1)), new AccountId(generated.get(i))))
                    .as("id[%d] must sort before id[%d] despite the clock stepping backwards", i - 1, i)
                    .isNegative();
        }
    }
}
