package io.github.essandhu.ledger.config;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.UUIDClock;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.essandhu.ledger.application.port.out.IdGenerator;

/**
 * The one source of identifiers (PLAN §4.3): UUIDv7 via java-uuid-generator. Application-side
 * generation keeps ids opaque, time-ordered for B-tree locality, and independent of the
 * database (PostgreSQL 18's native uuidv7() exists but two id sources would be one too many).
 *
 * <p>Monotonic within the process BY CONSTRUCTION, not by hope: PostingJpaRepository reads one
 * entry's legs back {@code ORDER BY id} and relies on "id order IS leg order" (I11's positional
 * reversal compare, GET's leg rendering), so ids handed out sequentially must sort strictly
 * ascending. JUG's TimeBasedEpochGenerator alone does not guarantee that — its same-millisecond
 * counter engages only on exact timestamp equality, so a wall clock stepping BACKWARDS (NTP
 * correction, VM resume) mints a smaller id. Two measures close the hole: the
 * {@link MonotoneUuidClock} clamps the timestamp to {@code max(wall, last-returned)} (never
 * re-reads old time; on the equal-millisecond path JUG's internal +1 entropy counter IS
 * monotonic), and the generate call is synchronized as a whole, making clock-read + construct
 * atomic — without that, a lagging thread could slip an older timestamp into the generator
 * between two of another thread's sequential draws and reset the counter entropy under it. The
 * lock adds nothing measurable: JUG already serializes construction internally.
 */
@Configuration(proxyBeanMethods = false)
class IdGeneratorConfig {

    @Bean
    IdGenerator idGenerator() {
        // UUIDClock.systemTimeClock(), not a raw System.currentTimeMillis(): ambient time
        // stays behind an injectable seam, and this package is its one sanctioned home.
        return monotonicUuidV7(UUIDClock.systemTimeClock());
    }

    /** The generator over an injectable wall clock, so the monotonicity contract is testable. */
    static IdGenerator monotonicUuidV7(UUIDClock wall) {
        TimeBasedEpochGenerator uuidV7 =
                Generators.timeBasedEpochGenerator(null, new MonotoneUuidClock(wall));
        return () -> {
            synchronized (uuidV7) {
                return uuidV7.generate();
            }
        };
    }

    /**
     * {@code max(wall, last-returned)} — a monotone non-decreasing view of the wall clock.
     * After a backwards step the generator keeps stamping the high-water millisecond (JUG's
     * counter path), trading a briefly stale timestamp inside the id for order that never
     * lies; {@code posted_at} correctness is untouched — business time comes from the Clock
     * bean, never from id bits.
     */
    static final class MonotoneUuidClock extends UUIDClock {

        private final UUIDClock wall;
        private long last = Long.MIN_VALUE;

        MonotoneUuidClock(UUIDClock wall) {
            this.wall = wall;
        }

        @Override
        public synchronized long currentTimeMillis() {
            long now = wall.currentTimeMillis();
            if (now > last) {
                last = now;
            }
            return last;
        }
    }
}
