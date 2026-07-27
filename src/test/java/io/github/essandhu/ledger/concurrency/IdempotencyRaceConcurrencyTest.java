package io.github.essandhu.ledger.concurrency;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.github.essandhu.ledger.support.concurrent.StressRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I8, the racy half (workload d): one idempotency key fired from K threads
 * posts EXACTLY ONE entry, ever — M4 proved the two-thread races deterministic-ish;
 * this is the promised hammer, and it drives the HTTP surface so both settlement layers of
 * ADR-0004 are on the path: same-payload duplicates serialize on the balance locks and answer
 * replay from the under-lock re-read; duplicates sharing no lock die on V3's backstop unique
 * index and live through the web adapter's single fresh-transaction retry
 * ({@code PostingResponses.withLostRaceRetry}) — the layer a port-level test would bypass.
 *
 * <p>Three interleaving families, each asserted from database truth (one row under the
 * (created_by, idempotency_key) keyspace) with client statuses as the cross-check:
 * same payload (K−1 byte-identical replays), tampered payloads on shared accounts (K−1 typed
 * conflicts), and tampered payloads on DISJOINT account pairs — no shared lock anywhere, so
 * only the backstop index can arbitrate.
 */
@DisplayName("I8 (racy): one idempotency key from K threads → exactly one entry, however the race falls")
class IdempotencyRaceConcurrencyTest extends ConcurrencyTestSupport {

    @Test
    @DisplayName("I8: K threads, same key, same payload → one 201, K−1 byte-identical 200-replays, one entry, money moved once")
    void same_key_same_payload_posts_exactly_once() {
        int threads = StressRunner.threads(8);
        int rounds = StressRunner.iterations(10);
        String subject = subject("i8-same");
        String source = createAccount(marker("i8s-src"), true);
        String target = createAccount(marker("i8s-tgt"), true);

        for (int round = 0; round < rounds; round++) {
            String key = "i8-%s-r%d".formatted(subject, round);
            String json = transferJson(source, target, 60);

            List<MvcTestResult> results = StressRunner.run(threads, StressRunner.bound(threads),
                    worker -> () -> postTransfer(subject, key, json));

            List<MvcTestResult> created = results.stream()
                    .filter(r -> r.getResponse().getStatus() == 201).toList();
            List<MvcTestResult> replayed = results.stream()
                    .filter(r -> r.getResponse().getStatus() == 200).toList();
            assertThat(created.size())
                    .as("round %d: exactly one winner — two would be a double-post", round)
                    .isEqualTo(1);
            assertThat(replayed.size())
                    .as("round %d: every loser replays — never a domain 422, never a 500", round)
                    .isEqualTo(threads - 1);
            String winnerBody = body(created.get(0));
            for (MvcTestResult replay : replayed) {
                assertThat(replay.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
                assertThat(body(replay))
                        .as("round %d: replays serve the winner's stored response byte for byte", round)
                        .isEqualTo(winnerBody);
            }
            assertThat(entryCount(subject))
                    .as("round %d: one entry per round's key, however the race fell", round)
                    .isEqualTo(round + 1);
        }

        // Money moved exactly once per round — the I8 sum, from the snapshot the locks guard.
        assertThat(balanceRow(source).balance()).isEqualTo(60L * rounds);
        assertThat(balanceRow(target).balance()).isEqualTo(-60L * rounds);
        assertSnapshotEqualsPostings(source);
        assertSnapshotEqualsPostings(target);
    }

    @Test
    @DisplayName("I9 under race: K threads, same key, K DIFFERENT payloads on shared accounts → one 201, K−1 typed conflicts, winner's amount on the books")
    void same_key_tampered_payloads_one_winner_rest_conflict() {
        int threads = StressRunner.threads(8);
        String subject = subject("i8-tamper");
        String source = createAccount(marker("i8t-src"), true);
        String target = createAccount(marker("i8t-tgt"), true);
        String key = "i8-tamper-" + subject;

        // Worker w bids amount 10+w — every payload canonically distinct, all sharing both
        // balance locks, so losers settle via the under-lock re-read: conflict, never a
        // misfiled domain rejection, never a double-post.
        List<MvcTestResult> results = StressRunner.run(threads, StressRunner.bound(threads),
                worker -> () -> postTransfer(subject, key, transferJson(source, target, 10 + worker)));

        List<Integer> statuses = results.stream()
                .map(r -> r.getResponse().getStatus()).toList();
        long winners = statuses.stream().filter(s -> s == 201).count();
        assertThat(winners).as("exactly one bid claims the key").isEqualTo(1);
        for (int i = 0; i < results.size(); i++) {
            MvcTestResult result = results.get(i);
            if (result.getResponse().getStatus() == 201) {
                continue;
            }
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(problemType(result))
                    .as("worker %d: a tampered duplicate is the TYPED conflict — the slug that names the client bug", i)
                    .isEqualTo(PROBLEMS + "idempotency-key-conflict");
        }

        // Database truth: one entry, and the books carry the WINNER's amount exactly once.
        assertThat(entryCount(subject)).isEqualTo(1);
        int winnerIndex = statuses.indexOf(201);
        assertThat(balanceRow(source).balance())
                .as("only the winning bid moved money")
                .isEqualTo(10L + winnerIndex);
        assertSnapshotEqualsPostings(source);
        assertSnapshotEqualsPostings(target);
    }

    @Test
    @DisplayName("I8 with NO shared locks: same key over disjoint account pairs → the backstop index arbitrates, the web retry answers the conflict")
    void same_key_disjoint_accounts_settles_on_the_backstop_index() {
        // Disjoint pairs mean the under-lock re-read can never see the winner — the loser's
        // OWN insert trips journal_entry_idem_backstop, and the web adapter's one retry in a
        // fresh transaction reads the winner's committed record and answers the conflict
        // (ADR-0004's designed ending; a raw 500 here means the retry layer is broken).
        int threads = StressRunner.threads(4);
        String subject = subject("i8-disjoint");
        String key = "i8-disjoint-" + subject;
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            sources.add(createAccount(marker("i8d-src" + w), true));
            targets.add(createAccount(marker("i8d-tgt" + w), true));
        }

        List<MvcTestResult> results = StressRunner.run(threads, StressRunner.bound(threads),
                worker -> () -> postTransfer(subject, key,
                        transferJson(sources.get(worker), targets.get(worker), 25 + worker)));

        List<Integer> statuses = results.stream()
                .map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses.stream().filter(s -> s == 201).count())
                .as("exactly one pair's transfer claims the key")
                .isEqualTo(1);
        for (int w = 0; w < threads; w++) {
            if (statuses.get(w) == 201) {
                continue;
            }
            assertThat(results.get(w)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(problemType(results.get(w)))
                    .isEqualTo(PROBLEMS + "idempotency-key-conflict");
        }

        assertThat(entryCount(subject)).as("the backstop held: one entry ever").isEqualTo(1);
        int winner = statuses.indexOf(201);
        for (int w = 0; w < threads; w++) {
            long expected = w == winner ? 25L + w : 0L;
            assertThat(balanceRow(sources.get(w)).balance())
                    .as("pair %d: %s", w, w == winner ? "the winner's money moved once" : "losers' accounts untouched")
                    .isEqualTo(expected);
            assertThat(balanceRow(targets.get(w)).balance()).isEqualTo(-expected);
            assertSnapshotEqualsPostings(sources.get(w));
            assertSnapshotEqualsPostings(targets.get(w));
        }
    }
}
