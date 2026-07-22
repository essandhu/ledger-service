package io.github.essandhu.ledger.adapter.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Bean-validation face of the domain's {@code CurrencyCode} rule, so invalid currencies are a
 * 400 validation problem at the web boundary (request well-formedness) and the domain guard
 * underneath stays intentionally-shadowed defense in depth.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = CurrencyCodeValidator.class)
@interface ValidCurrency {

    String message() default "must be an uppercase ISO 4217 currency that denominates money";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
