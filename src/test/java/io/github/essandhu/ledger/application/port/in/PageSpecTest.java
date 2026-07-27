package io.github.essandhu.ledger.application.port.in;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Port-side guards for offset pagination: the web layer 400s first, but PageSpec is the
 * contract for non-HTTP callers too (the dual-validation stance StatementSpecTest pins).
 */
@DisplayName("PageSpec: constructor guards and their boundaries")
class PageSpecTest {

    @Test
    @DisplayName("page is zero-based: 0 is the first page, negatives are rejected")
    void page_is_zero_based() {
        assertThat(new PageSpec(0, 10).page()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> new PageSpec(-1, 10));
    }

    @Test
    @DisplayName("size is bounded to [1, MAX_SIZE], both ends inclusive")
    void size_is_bounded_inclusive() {
        assertThat(new PageSpec(0, 1).size()).isEqualTo(1);
        assertThat(new PageSpec(0, PageSpec.MAX_SIZE).size()).isEqualTo(PageSpec.MAX_SIZE);
        assertThatIllegalArgumentException().isThrownBy(() -> new PageSpec(0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PageSpec(0, PageSpec.MAX_SIZE + 1));
    }
}
