package io.github.essandhu.ledger.domain.error;

import java.util.Map;
import java.util.stream.Collectors;

import io.github.essandhu.ledger.domain.model.CurrencyCode;

/**
 * I1 violated: the draft's legs do not net to exactly zero in every currency. Balance is judged
 * per currency with exact integer equality — one minor unit off is unbalanced, and there is no
 * tolerance band (PLAN §4.2: balancing is literally {@code SUM(amount) = 0} per currency).
 * Carries the nonzero residuals so the problem response can say precisely which currencies leak
 * how many minor units, instead of a bare "does not balance". Maps to 422.
 */
public class UnbalancedEntry extends RuntimeException {

    private final Map<CurrencyCode, Long> residuals;

    public UnbalancedEntry(Map<CurrencyCode, Long> residuals) {
        super("entry does not balance: residual %s (I1: every currency must net to exactly zero)"
                .formatted(render(residuals)));
        this.residuals = Map.copyOf(residuals);
    }

    /**
     * The per-currency sums that failed to reach zero, in minor units (debit-positive: a
     * positive residual is excess debit). Only imbalanced currencies appear — a currency that
     * nets to zero is not the problem.
     */
    public Map<CurrencyCode, Long> residuals() {
        return residuals;
    }

    private static String render(Map<CurrencyCode, Long> residuals) {
        return residuals.entrySet().stream()
                .map(entry -> entry.getKey().value() + " " + entry.getValue())
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
