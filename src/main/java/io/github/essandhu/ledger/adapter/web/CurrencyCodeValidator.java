package io.github.essandhu.ledger.adapter.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import io.github.essandhu.ledger.domain.error.InvalidAccountInput;
import io.github.essandhu.ledger.domain.model.CurrencyCode;

/** Delegates to the domain rule — one source of truth for what a valid currency is. */
class CurrencyCodeValidator implements ConstraintValidator<ValidCurrency, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotNull's job
        }
        try {
            new CurrencyCode(value);
            return true;
        } catch (InvalidAccountInput invalid) {
            return false;
        }
    }
}
