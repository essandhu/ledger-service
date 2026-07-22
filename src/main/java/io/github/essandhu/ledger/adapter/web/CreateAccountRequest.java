package io.github.essandhu.ledger.adapter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.github.essandhu.ledger.domain.model.AccountType;

/**
 * POST /accounts body (PLAN §5: name, currency, type, allowNegative). {@code status} is
 * declared only to be rejected loudly ({@link FieldNotWritable}) — new accounts are always
 * ACTIVE, and a client asking otherwise must not be silently ignored. Declared as String so
 * ANY status value (even garbage) gets the 422, not an enum-parse 400.
 */
record CreateAccountRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @ValidCurrency String currency,
        @NotNull AccountType type,
        @NotNull Boolean allowNegative,
        String status) {
}
