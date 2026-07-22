package io.github.essandhu.ledger.config;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single home of ambient time (TEST-STRATEGY §1): production code reads time only through
 * this injected Clock, and the ArchUnit no-ambient-time rule allows system-clock construction
 * only in this package. Tests substitute fixed clocks.
 *
 * <p>Ticked to microseconds: PostgreSQL timestamptz stores microsecond precision, while the
 * raw system clock resolves finer (nanos on Linux, 100 ns on Windows). Without the tick, a
 * POST/PATCH response body (serialized from the in-memory Instant) would disagree in its last
 * digits with every subsequent GET (read back from the database) — phantom modifications to
 * any client comparing timestamps. Aligning at the source makes write responses byte-identical
 * to round-tripped values; an integration test pins the equality.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS));
    }
}
