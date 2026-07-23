package io.github.essandhu.ledger.adapter.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Strict positivity of a {@link MoneyDto} amount — see {@link PositiveAmount} for the why. */
class PositiveAmountValidator implements ConstraintValidator<PositiveAmount, MoneyDto> {

    @Override
    public boolean isValid(MoneyDto value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotNull's job
        }
        return value.amount() > 0;
    }
}
