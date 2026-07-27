package io.github.essandhu.ledger.config;

import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Active ONLY during the Dockerfile's CDS training run ({@code -Dspring.profiles.active=
 * cds-training}), never in a served environment. The training run must complete a full context
 * refresh with no database present, and the one bean that hard-requires a live connection at
 * creation is Batch's JDBC {@code JobRepository} (it sniffs JDBC metadata for its database
 * type — no property can pin it). Registering this subclass makes {@code
 * BatchJdbcAutoConfiguration} back off ({@code @ConditionalOnMissingBean(DefaultBatchConfiguration
 * .class)}), and Batch 6's base configuration wires a {@code ResourcelessJobRepository} — the
 * whole Batch graph, including the {@code JobOperator} the reconciliation launcher injects,
 * then builds without touching a DataSource. The Flyway/Hibernate counterparts of this dodge
 * are plain properties and live in application-cds-training.yaml.
 */
@Configuration(proxyBeanMethods = false)
@Profile("cds-training")
class CdsTrainingBatchConfiguration extends DefaultBatchConfiguration {
}
