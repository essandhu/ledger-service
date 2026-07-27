package io.github.essandhu.ledger.application.port.in;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entry guards for the balance reading: postingCount is the statement-walk verifier
 *, so a figure claiming to aggregate a negative number of postings is nonsense
 * the record refuses to carry.
 */
@DisplayName("BalanceView: constructor guards")
class BalanceViewTest {

    private static final AccountId ACCOUNT = new AccountId(UUID.randomUUID());
    private static final CurrencyCode USD = new CurrencyCode("USD");

    @Test
    @DisplayName("a zero-posting balance is legal — a fresh account reconciles to nothing")
    void zero_posting_count_constructs() {
        BalanceView view = new BalanceView(
                ACCOUNT, AccountType.ASSET, USD, 0, 0, 0, Optional.empty());
        assertThat(view.postingCount()).isZero();
    }

    @Test
    @DisplayName("a negative postingCount is rejected, naming the value")
    void negative_posting_count_is_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BalanceView(
                        ACCOUNT, AccountType.ASSET, USD, 0, 0, -7, Optional.empty()))
                .withMessageContaining("-7");
    }

    @Test
    @DisplayName("nulls are refused at construction (records validate on entry)")
    void nulls_are_refused() {
        assertThatThrownBy(() -> new BalanceView(
                null, AccountType.ASSET, USD, 0, 0, 0, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BalanceView(
                ACCOUNT, null, USD, 0, 0, 0, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BalanceView(
                ACCOUNT, AccountType.ASSET, null, 0, 0, 0, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BalanceView(
                ACCOUNT, AccountType.ASSET, USD, 0, 0, 0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
