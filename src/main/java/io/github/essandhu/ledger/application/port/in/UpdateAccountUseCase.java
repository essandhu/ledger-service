package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;
import java.util.Optional;

import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;

/**
 * PATCH /accounts/{id}: rename and/or lifecycle transition as ONE atomic operation.
 *
 * <p>Deviation from the package layout's sketch, which listed a {@code ChangeAccountStatusUseCase}:
 * PATCH carries name and status together, and the transaction boundary lives in the service —
 * two ports would mean two transactions per request (or an orchestrator the architecture
 * doesn't have). One port per API operation keeps the PATCH atomic; §3.1 was reconciled.
 * In M2 the CLOSED branch additionally evaluates the zero-balance precondition under the
 * account_balance lock, inside this same use case.
 */
public interface UpdateAccountUseCase {

    Account update(UpdateAccountCommand command);

    record UpdateAccountCommand(AccountId id, Optional<String> newName, Optional<AccountStatus> newStatus) {
        public UpdateAccountCommand {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(newName, "newName");
            Objects.requireNonNull(newStatus, "newStatus");
        }
    }
}
