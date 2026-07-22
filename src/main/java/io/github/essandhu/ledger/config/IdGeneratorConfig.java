package io.github.essandhu.ledger.config;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.essandhu.ledger.application.port.out.IdGenerator;

/**
 * The one source of identifiers (PLAN §4.3): UUIDv7 via java-uuid-generator. Application-side
 * generation keeps ids opaque, time-ordered for B-tree locality, and independent of the
 * database (PostgreSQL 18's native uuidv7() exists but two id sources would be one too many).
 * JUG's generator is thread-safe and monotonic within the process.
 */
@Configuration(proxyBeanMethods = false)
class IdGeneratorConfig {

    @Bean
    IdGenerator idGenerator() {
        TimeBasedEpochGenerator uuidV7 = Generators.timeBasedEpochGenerator();
        return uuidV7::generate;
    }
}
