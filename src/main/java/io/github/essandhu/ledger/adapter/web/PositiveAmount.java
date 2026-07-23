package io.github.essandhu.ledger.adapter.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * {@code @Positive} for a {@link MoneyDto}-typed field — bean validation's numeric constraints
 * cannot reach inside a nested record, so this delegating constraint exists (the
 * {@link ValidCurrency} pattern). PLAN §5 pins the transfer's sign semantics: source = debit =
 * {@code +amount}, target = credit = {@code −amount} — a zero or negative amount would merely
 * swap the roles, so the DTO refuses to let clients express that confusion (400: shape, not
 * business state; the domain's zero-leg rule I2 still backstops zero for non-HTTP callers).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = PositiveAmountValidator.class)
@interface PositiveAmount {

    String message() default "must be strictly positive in minor units";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
