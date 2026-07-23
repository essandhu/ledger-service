package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;

import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Money;

/**
 * POST /transfers (PLAN §5): the two-leg convenience over the same posting engine. Sign
 * semantics are PLAN §5, literally: the SOURCE leg is the DEBIT, {@code +amount}; the TARGET
 * leg is the CREDIT, {@code −amount} — the debit-positive convention of PLAN §4.2 applied to
 * a single {@link Money}, so the pair is balanced by construction and same-currency by
 * construction. Whether that currency matches each account's, and every other
 * account-dependent rule, is decided like any entry: under the balance lock (ADR-0003).
 *
 * <p>Strict positivity of the amount is the DTO layer's 400 (a shape rule); the domain's
 * zero-leg rule (I2) still backstops zero for non-HTTP callers. A negative amount would merely
 * swap the roles — the DTO guard exists so clients cannot express that confusion.
 */
public interface TransferFundsUseCase {

    JournalEntry transfer(TransferCommand command);

    record TransferCommand(AccountId source, AccountId target, Money amount, String description,
            String createdBy) {
        public TransferCommand {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(createdBy, "createdBy");
            // description is nullable (absence means absence) and validated by the domain
        }
    }
}
