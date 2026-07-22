package io.github.essandhu.ledger.adapter.web;

import jakarta.validation.constraints.Size;

import io.github.essandhu.ledger.domain.model.AccountStatus;

/**
 * PATCH /accounts/{id} body: declarative partial update — absent fields stay untouched,
 * name and/or status may be set (one atomic operation). The immutable attributes
 * (type/currency/allowNegative, PLAN §5) are declared as loosely-typed fields solely so any
 * attempt to write them — whatever the value — is rejected with 422 {@link FieldNotWritable}
 * instead of being silently dropped.
 */
record UpdateAccountRequest(
        @Size(max = 200) String name,
        AccountStatus status,
        String type,
        String currency,
        Boolean allowNegative) {
}
