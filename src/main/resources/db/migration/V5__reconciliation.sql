-- I15 (M6): the reconciliation record tables (PLAN §4.3, ADR-0002) — reconciliation_run and
-- reconciliation_finding. The job recomputes every account's (SUM(amount), COUNT(*)) from
-- postings and compares it to the (balance, posting_count) snapshot pair; a run row records
-- each sweep's verdict, a finding row records each drifted account. Findings are flag-and-alert
-- artifacts, never inputs to repair — ADR-0002 pins "flag, alert, investigate; never
-- auto-repair", which is why nothing in this schema (or the grants below) offers a correction
-- path.
--
-- Two deliberate extensions of the PLAN §4.3 ER sketch, recorded at M6:
--   * findings carry snapshot_count/computed_count alongside the balances — the posting_count
--     watermark comparison is load-bearing (ADR-0002: compensating corruption can leave SUM
--     intact while the count diverges), so a count-only drift must be expressible as a finding;
--   * runs carry triggered_by (PLAN §7 auditability: journal_entry.created_by precedent) and
--     the three integrity-check counters (currency/posted_at denormalization re-verification per
--     PLAN §4.3, and the I5 global per-currency zero-sum re-check per ADR-0002's Proof section),
--     because a check the job performs but does not record is a check nobody can audit later.
--
-- ADRs in force: ADR-0002 (snapshot + watermark, the reconciliation safety net this schema
-- records), ADR-0001 (balances and deltas are integer minor units — bigint end to end),
-- ADR-0003 (the job takes NO locks: a single statement's READ COMMITTED snapshot is already
-- consistent with the atomic posting transactions it observes).

CREATE TABLE reconciliation_run (
    -- Application-generated UUIDv7 (PLAN §4.3: exactly one ID source). Also the identifying
    -- Spring Batch JobParameter, tying BATCH_JOB_INSTANCE to this row for cross-audit.
    id                       uuid        PRIMARY KEY,
    -- Both from the injected Clock via the application service; NO DEFAULT now(), as everywhere
    -- (the V2 principle: ambient time below the application is invisible to the ArchUnit rule).
    started_at               timestamptz NOT NULL,
    finished_at              timestamptz NULL,
    CONSTRAINT reconciliation_run_finish_after_start
        CHECK (finished_at >= started_at),
    -- RUNNING until the finish update; CLEAN/DRIFT are the job's verdict; FAILED records a run
    -- that died before producing one (its result columns stay NULL — partial counts from an
    -- aborted sweep would be a lie with decimals).
    status                   text        NOT NULL
                                         CONSTRAINT reconciliation_run_status_valid CHECK
                                         (status IN ('RUNNING', 'CLEAN', 'DRIFT', 'FAILED')),
    -- Who asked: the admin trigger records the JWT subject, the scheduler records 'scheduler'
    -- (bare text NOT NULL — the journal_entry.created_by shape).
    triggered_by             text        NOT NULL,
    -- Results, all NULL until the finish update writes them together (shape CHECK below).
    accounts_checked         bigint      NULL,
    drift_count              bigint      NULL,
    -- posting.currency <> account.currency occurrences (denormalization re-verification).
    currency_mismatch_count  bigint      NULL,
    -- posting.posted_at <> journal_entry.posted_at occurrences (the other denormalization).
    posted_at_mismatch_count bigint      NULL,
    -- Currencies whose global SUM(amount) over all postings is nonzero (I5 re-verified at rest).
    unbalanced_currency_count bigint     NULL,
    CONSTRAINT reconciliation_run_counts_nonneg CHECK (
        accounts_checked >= 0 AND drift_count >= 0 AND currency_mismatch_count >= 0
        AND posted_at_mismatch_count >= 0 AND unbalanced_currency_count >= 0),
    -- A run is finished exactly when its status says so ...
    CONSTRAINT reconciliation_run_finished_shape
        CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    -- ... and carries results exactly when it has a verdict: CLEAN/DRIFT rows are fully
    -- populated, RUNNING/FAILED rows are fully unpopulated.
    CONSTRAINT reconciliation_run_results_shape CHECK (
        (status IN ('CLEAN', 'DRIFT')) = (accounts_checked IS NOT NULL
            AND drift_count IS NOT NULL AND currency_mismatch_count IS NOT NULL
            AND posted_at_mismatch_count IS NOT NULL AND unbalanced_currency_count IS NOT NULL)),
    -- The verdict must match the numbers it summarizes — the domain decides first, this mirrors
    -- it as defense in depth against non-API writes (the V2 rationale).
    CONSTRAINT reconciliation_run_verdict_matches_counts CHECK (
        (status <> 'CLEAN' OR (drift_count = 0 AND currency_mismatch_count = 0
            AND posted_at_mismatch_count = 0 AND unbalanced_currency_count = 0))
        AND (status <> 'DRIFT' OR (drift_count > 0 OR currency_mismatch_count > 0
            OR posted_at_mismatch_count > 0 OR unbalanced_currency_count > 0)))
);

-- INSERT: the open-run row (status RUNNING) written when the job starts; UPDATE: the single
-- set-based finish statement (status, finished_at, result counts — the account_balance bump
-- precedent: no read-modify-write). No DELETE — runs are audit history, and the one DELETE
-- precedent (V4's idempotency_record) is justified as expiring diagnostics, which this is not.
GRANT SELECT, INSERT, UPDATE ON reconciliation_run TO ledger_app;

-- Self-verification (the V1..V4 pattern, I16): the ACL must record EXACTLY
-- {SELECT, INSERT, UPDATE} for ledger_app, so any privilege creep fails the migration loudly.
DO $$
DECLARE
    granted text;
BEGIN
    SELECT string_agg(a.privilege_type, ',' ORDER BY a.privilege_type)
    INTO granted
    FROM pg_class c, aclexplode(c.relacl) a
    WHERE c.oid = 'public.reconciliation_run'::regclass
      AND a.grantee = 'ledger_app'::regrole;
    IF granted IS DISTINCT FROM 'INSERT,SELECT,UPDATE' THEN
        RAISE EXCEPTION 'grant model not established: ledger_app grants on reconciliation_run are [%], expected [INSERT,SELECT,UPDATE]',
            COALESCE(granted, 'none');
    END IF;
END
$$;

CREATE TABLE reconciliation_finding (
    -- Application-generated UUIDv7; time-ordered ids make ORDER BY id the insertion order,
    -- which is the findings-listing order (the account listing precedent, PLAN §5).
    id               uuid   PRIMARY KEY,
    run_id           uuid   NOT NULL REFERENCES reconciliation_run (id),
    account_id       uuid   NOT NULL REFERENCES account (id),
    -- The snapshot pair as the run saw it (ADR-0002: balance AND watermark) ...
    snapshot_balance bigint NOT NULL,
    snapshot_count   bigint NOT NULL,
    -- ... and the pair recomputed from postings in the same single statement.
    computed_balance bigint NOT NULL,
    computed_count   bigint NOT NULL,
    -- snapshot minus computed: positive = the snapshot claims money the postings cannot back.
    delta            bigint NOT NULL,
    CONSTRAINT reconciliation_finding_counts_nonneg
        CHECK (snapshot_count >= 0 AND computed_count >= 0),
    -- delta is DERIVED; storing it buys indexable/report-friendly access, this keeps it honest.
    CONSTRAINT reconciliation_finding_delta_consistent
        CHECK (delta = snapshot_balance - computed_balance),
    -- A finding exists iff drift exists: at least one half of the pair must actually differ.
    CONSTRAINT reconciliation_finding_records_drift
        CHECK (snapshot_balance <> computed_balance OR snapshot_count <> computed_count),
    -- One verdict per account per sweep; doubles as the run_id lookup index for the findings
    -- listing and the finish update's aggregate.
    CONSTRAINT reconciliation_finding_once_per_run UNIQUE (run_id, account_id)
);

-- I3-adjacent: findings are recorded, never edited or removed — write-once audit artifacts,
-- same grant shape as journal_entry/posting.
GRANT SELECT, INSERT ON reconciliation_finding TO ledger_app;

DO $$
DECLARE
    granted text;
BEGIN
    SELECT string_agg(a.privilege_type, ',' ORDER BY a.privilege_type)
    INTO granted
    FROM pg_class c, aclexplode(c.relacl) a
    WHERE c.oid = 'public.reconciliation_finding'::regclass
      AND a.grantee = 'ledger_app'::regrole;
    IF granted IS DISTINCT FROM 'INSERT,SELECT' THEN
        RAISE EXCEPTION 'grant model not established: ledger_app grants on reconciliation_finding are [%], expected [INSERT,SELECT]',
            COALESCE(granted, 'none');
    END IF;
END
$$;
