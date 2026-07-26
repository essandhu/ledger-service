package io.github.essandhu.ledger.concurrency;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.github.essandhu.ledger.support.concurrent.StressRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I6, the racy half (TEST-STRATEGY §4, workload c): withdrawals racing over a small balance on
 * an {@code allowNegative = false} account — WITH deposits racing them the other way. This is
 * ADR-0003's race #2 (two withdrawals both passing the check on a stale read) hammered
 * directly, and the mixed traffic is load-bearing: with withdrawals alone the balance is
 * monotone decreasing and identical amounts make every prefix minimum equal the final state,
 * so a history walk would merely restate the final assertions. Racing deposits create the
 * interleavings where the walk has independent teeth — a withdrawal committed against money
 * that was not there YET (judged on a stale or dirty read of an in-flight deposit) can end at
 * a healthy-looking final balance, and only the prefix replay convicts it.
 *
 * <p>Two verdicts, per the §4 design: the FINAL state (balance = funded + deposits − grants,
 * never negative), and the FULL posting history — replayed in ledger order {@code (posted_at,
 * id)}, the account's natural balance must be non-negative at every prefix, because
 * per-account posted_at order equals commit order by construction (PLAN §4.6). The honesty
 * rule: deposit workers may see nothing but 201 (a deposit into a strict account is always
 * legal); withdrawal workers classify 201 and the typed 422 {@code overdraft}; any other
 * answer fails the run. Grant counts are read from the DATABASE, then cross-checked against
 * the client tally.
 */
@DisplayName("I6 (racy): a strict account never observes a negative natural balance under racing withdrawals and deposits")
class OverdraftRaceConcurrencyTest extends ConcurrencyTestSupport {

    /** Initial funding, per-withdrawal, and per-deposit amounts. Deliberately co-prime-ish
     * and unequal: unequal movements make prefix minima genuinely order-dependent. Sized so
     * withdrawal capacity always exceeds total inflow — rejections are certain at ANY knob
     * setting (7·⌈T/2⌉·M > 40 + 3·⌊T/2⌋·M for every T ≥ 1, M ≥ 1 with ⌊T/2⌋·M ≥ 10 at
     * defaults; the exact bound is asserted arithmetically below, not assumed). */
    private static final long FUNDED = 40;
    private static final long WITHDRAWAL = 7;
    private static final long DEPOSIT = 3;

    @Test
    @DisplayName("I6: racing withdrawals vs deposits — grants and typed 422s only, and no prefix of the ledger history dips below zero")
    void racing_withdrawals_and_deposits_never_overdraw() {
        int threads = StressRunner.threads(8);
        int attemptsPerThread = StressRunner.iterations(3);
        String subject = subject("i6-racer");
        String strict = createAccount(marker("i6-strict"), false);
        String sink = createAccount(marker("i6-sink"), true);

        // Arm the strict account with exactly FUNDED units (+40 raw: an ASSET debit).
        MvcTestResult funding = postJournal(subject, "i6-fund-" + subject,
                journalJson(strict, sink, FUNDED));
        assertThat(funding).hasStatus(HttpStatus.CREATED);

        // Even workers withdraw (credit strict, −7 raw — natural falls), odd workers deposit
        // (debit strict, +3 raw). Deposits are ALWAYS legal against a strict account, so a
        // deposit worker classifies nothing — any non-201 is unexpected. Withdrawal workers
        // classify granted vs the typed overdraft 422; anything else fails the run (§4:
        // rejected requests fail with the DOMAIN error, never a lock error).
        int withdrawers = (threads + 1) / 2;
        int depositors = threads / 2;
        List<Integer> grantedPerWorker = StressRunner.run(threads,
                StressRunner.bound((long) threads * attemptsPerThread), worker -> () -> {
                    boolean withdrawing = worker % 2 == 0;
                    int granted = 0;
                    for (int i = 0; i < attemptsPerThread; i++) {
                        String key = "i6-%s-w%d-%d".formatted(subject, worker, i);
                        MvcTestResult result = withdrawing
                                ? postJournal(subject, key, journalJson(sink, strict, WITHDRAWAL))
                                : postJournal(subject, key, journalJson(strict, sink, DEPOSIT));
                        int status = result.getResponse().getStatus();
                        if (status == 201) {
                            granted++;
                        } else if (withdrawing && status == 422
                                && (PROBLEMS + "overdraft").equals(problemType(result))) {
                            // The expected rejection — counted, not swallowed (§4).
                        } else {
                            throw unexpectedResponse(
                                    (withdrawing ? "withdrawal " : "deposit ") + key, result);
                        }
                    }
                    return granted;
                });

        // Deposits never reject: every odd worker's count is exactly its attempts.
        long deposits = (long) depositors * attemptsPerThread;
        long clientDeposits = 0;
        long clientGrants = 0;
        for (int worker = 0; worker < threads; worker++) {
            if (worker % 2 == 0) {
                clientGrants += grantedPerWorker.get(worker);
            } else {
                clientDeposits += grantedPerWorker.get(worker);
            }
        }
        assertThat(clientDeposits).as("a deposit into a strict account is always legal")
                .isEqualTo(deposits);

        // Database truth: strict postings = 1 funding + deposits + grants.
        long grantedInDb = balanceRow(strict).postingCount() - 1 - deposits;
        assertThat(grantedInDb)
                .as("every 201 committed exactly one movement — client and database agree")
                .isEqualTo(clientGrants);
        long withdrawalAttempts = (long) withdrawers * attemptsPerThread;
        long maxGrants = (FUNDED + DEPOSIT * deposits) / WITHDRAWAL;
        assertThat(grantedInDb)
                .as("total inflow bounds the grants — one more IS the overdraft")
                .isLessThanOrEqualTo(maxGrants);
        assertThat(grantedInDb)
                .as("the first lock holder saw the full funding — someone must have been granted")
                .isPositive();
        // Fixture teeth, knob-proof: withdrawal capacity must exceed the total possible
        // inflow, or the workload cannot force a single rejection and the I6 judgment was
        // never exercised. (Holds at the defaults and any UP-crank; a pathological down-crank
        // fails HERE, loudly, instead of silently hammering nothing.) Combined with
        // grants <= maxGrants above, this guarantees at least one typed 422 occurred.
        assertThat(withdrawalAttempts)
                .as("sizing must keep the overdraft judgment contended: attempts > max grants")
                .isGreaterThan(maxGrants);

        // I6 from final state: exactly the arithmetic, and never negative (ASSET: raw IS the
        // natural balance).
        long finalBalance = balanceRow(strict).balance();
        assertThat(finalBalance)
                .isEqualTo(FUNDED + DEPOSIT * deposits - WITHDRAWAL * grantedInDb);
        assertThat(finalBalance).as("I6: natural balance never below zero").isNotNegative();

        // I6 from the full history — the verdict only this walk can deliver under mixed
        // traffic: replay the postings in ledger order; every prefix must be non-negative,
        // or some interleaving committed a withdrawal against money that was not there at
        // that point of history (even if later deposits made the FINAL balance look legal).
        long running = 0;
        for (long amount : postingAmountsInLedgerOrder(strict)) {
            running += amount;
            assertThat(running)
                    .as("I6 at every point of history: no prefix of the ledger dips below zero")
                    .isNotNegative();
        }
        assertThat(running).as("history replays to the snapshot").isEqualTo(finalBalance);

        // I4 cross-check on both touched accounts.
        assertSnapshotEqualsPostings(strict);
        assertSnapshotEqualsPostings(sink);
    }
}
