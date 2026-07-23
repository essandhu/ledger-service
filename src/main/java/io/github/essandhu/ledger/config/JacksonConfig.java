package io.github.essandhu.ledger.config;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strict JSON scalar binding for integer targets (ADR-0001). Jackson's defaults are lenient:
 * out of the box {@code {"amount": 10.99}} binds a {@code long} field as {@code 10} (float
 * truncation), {@code 1e2} as {@code 100}, and the JSON string {@code "250"} as {@code 250} —
 * so a fractional amount would silently mangle money instead of producing the 400 problem
 * ADR-0001 promises ("fractional or non-numeric amount is rejected"). Boot does not tighten
 * this; this customizer does, at the one shared choke point every {@code @RequestBody} runs
 * through, so the rule cannot be forgotten per DTO.
 *
 * <p>Float-shape input is rejected even when integer-valued ({@code 1e2}, {@code 100.0}):
 * ADR-0001's wire contract says amounts ARE JSON integers, not values that happen to round
 * cleanly — accepting integral floats would make {@code 10.99} vs {@code 10.00} a semantic
 * cliff instead of one uniform shape rule. String-shape is rejected for the same reason:
 * {@code "250"} is a string, and lenient coercion is exactly how the truncation bug family
 * enters. Scoped to {@link LogicalType#Integer} targets only ({@code int}/{@code long}/
 * {@code BigInteger} fields), so enum, UUID, boolean, and textual binding keep their default
 * semantics. Rejected input surfaces as {@code HttpMessageNotReadableException} → the standard
 * 400 problem body from {@code ResponseEntityExceptionHandler}, the same path as malformed
 * JSON; the proof lives in PostingApiIntegrationTest's 400-family cases.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictIntegerBinding() {
        return builder -> builder.withCoercionConfig(LogicalType.Integer, coercion -> coercion
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }
}
