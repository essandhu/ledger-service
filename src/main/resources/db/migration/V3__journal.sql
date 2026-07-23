-- I16 (M2): the journal tables (PLAN §4.3) — journal_entry, posting, account_balance — and the
-- I3 append-only grant model: the runtime role can record history but never rewrite it.
--
-- V2 forward-contract honored here: account_balance is created AND backfilled with one
-- zero-balance row per already-existing account (INSERT ... SELECT id, 0, 0, ... FROM account) —
-- the posting engine locks the balance row of every touched account (ADR-0003), so an account
-- without one would break the lock protocol. From M2 on, the create-account use case inserts the
-- snapshot row in the same transaction.
--
-- ADRs in force: ADR-0002 (one snapshot row per account, maintained in the posting transaction
-- under lock — hence the fillfactor), ADR-0003 (ordered FOR UPDATE locking lives on the
-- account_balance row, never the account row), ADR-0004 (the permanent idempotency backstop
-- index belongs on journal_entry from birth, even though header handling is M4).

CREATE TABLE journal_entry (
    id              uuid        PRIMARY KEY,
    entry_type      text        NOT NULL
                                CONSTRAINT journal_entry_type_valid CHECK
                                (entry_type IN ('TRANSFER', 'JOURNAL', 'REVERSAL')),
    -- Optional; when present it obeys the same text rules as account.name (any-whitespace blank,
    -- no control characters) at the larger 500-char budget (PLAN §4.3). NULL passes each CHECK by
    -- SQL three-valued logic, so no "IS NULL OR" noise is needed. Domain validates first; these
    -- mirror it as defense in depth against non-API writes (the V2 rationale).
    description     text        NULL
                                CONSTRAINT journal_entry_description_not_blank
                                CHECK (description ~ '\S')
                                CONSTRAINT journal_entry_description_no_control_chars
                                CHECK (description !~ '[[:cntrl:]]')
                                CONSTRAINT journal_entry_description_max_length
                                CHECK (char_length(description) <= 500),
    -- Set IFF the entry is a REVERSAL (PLAN §4.1): boolean equality rejects both a REVERSAL
    -- without a target and a plain entry claiming one.
    reversal_of     uuid        NULL REFERENCES journal_entry (id),
    CONSTRAINT journal_entry_reversal_shape
        CHECK ((entry_type = 'REVERSAL') = (reversal_of IS NOT NULL)),
    -- NULL until M4 wires the Idempotency-Key header; the backstop index below is permanent from
    -- birth (ADR-0004): long after M4's idempotency_record rows expire, this table — kept
    -- forever — remains the final guard against double-posting.
    idempotency_key text        NULL,
    -- The JWT subject that posted the entry (PLAN §4.3); also the idempotency scope (ADR-0004).
    created_by      text        NOT NULL,
    -- Assigned from the injected Clock while holding the balance locks (ADR-0003, PLAN §4.6).
    -- Deliberately NO DEFAULT now(): a database default would silently mask a missing Clock wire
    -- and reintroduce ambient time below the ArchUnit rule's visibility (the V2 principle).
    -- No version, no updated_at: rows are written exactly once (I3) — mutability columns would
    -- advertise an edit path that must not exist.
    posted_at       timestamptz NOT NULL
);

-- I11: an entry can be reversed at most once. Partial so the unbounded NULLs of non-reversal
-- entries stay out of the index; the application check under the balance locks is the friendly
-- error, this index is the backstop that makes the race lose loudly.
CREATE UNIQUE INDEX journal_entry_reversed_once
    ON journal_entry (reversal_of) WHERE reversal_of IS NOT NULL;

-- ADR-0004: the (creator, key) backstop against double-posting. Partial: keyless entries are
-- deliberately never deduplicated — only successful keyed outcomes are recorded, so a retry of a
-- rejected posting re-executes.
CREATE UNIQUE INDEX journal_entry_idem_backstop
    ON journal_entry (created_by, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- I3: entries are recorded, never edited or removed; the absent UPDATE/DELETE grants are the
-- database-level half of that guarantee (the API simply has no such operations).
GRANT SELECT, INSERT ON journal_entry TO ledger_app;

-- Self-verification (the V1/V2 pattern, I16): the ACL must record EXACTLY {SELECT, INSERT} for
-- ledger_app. An exact set — not UPDATE/DELETE-absence — so any privilege creep fails the
-- migration loudly in every environment it runs in, not only where the CI test suite runs.
DO $$
DECLARE
    granted text;
BEGIN
    SELECT string_agg(a.privilege_type, ',' ORDER BY a.privilege_type)
    INTO granted
    FROM pg_class c, aclexplode(c.relacl) a
    WHERE c.oid = 'public.journal_entry'::regclass
      AND a.grantee = 'ledger_app'::regrole;
    IF granted IS DISTINCT FROM 'INSERT,SELECT' THEN
        RAISE EXCEPTION 'grant model not established: ledger_app grants on journal_entry are [%], expected [INSERT,SELECT]',
            COALESCE(granted, 'none');
    END IF;
END
$$;

CREATE TABLE posting (
    id         uuid        PRIMARY KEY,
    entry_id   uuid        NOT NULL REFERENCES journal_entry (id),
    account_id uuid        NOT NULL REFERENCES account (id),
    -- Signed integer minor units, debit-positive (ADR-0001, PLAN §4.2). A zero leg moves
    -- nothing and means nothing — I2's database half; the domain rejects it first.
    amount     bigint      NOT NULL
                           CONSTRAINT posting_amount_nonzero CHECK (amount <> 0),
    -- Denormalized copy of the account's currency (PLAN §4.3) so M3 statements and as-of reads
    -- never join account. The domain enforces the match at post time; M6 reconciliation
    -- re-verifies. Same text + CHECK shape rationale as V2's account.currency.
    currency   text        NOT NULL
                           CONSTRAINT posting_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    -- Denormalized from the entry, for the statement index below; assigned under the balance
    -- locks like the entry's (ADR-0003, PLAN §4.6).
    posted_at  timestamptz NOT NULL
);

-- Covering path for M3 statements and as-of balance queries (PLAN §4.3): one account's postings
-- in time order, with id as the deterministic tiebreak for equal timestamps.
CREATE INDEX posting_account_statement ON posting (account_id, posted_at, id);
-- Entry reassembly (GET /journal-entries/{id}) and the reverse side of the entry_id FK.
CREATE INDEX posting_entry ON posting (entry_id);

-- I3: same append-only guarantee as journal_entry.
GRANT SELECT, INSERT ON posting TO ledger_app;

DO $$
DECLARE
    granted text;
BEGIN
    SELECT string_agg(a.privilege_type, ',' ORDER BY a.privilege_type)
    INTO granted
    FROM pg_class c, aclexplode(c.relacl) a
    WHERE c.oid = 'public.posting'::regclass
      AND a.grantee = 'ledger_app'::regrole;
    IF granted IS DISTINCT FROM 'INSERT,SELECT' THEN
        RAISE EXCEPTION 'grant model not established: ledger_app grants on posting are [%], expected [INSERT,SELECT]',
            COALESCE(granted, 'none');
    END IF;
END
$$;

CREATE TABLE account_balance (
    account_id    uuid        PRIMARY KEY REFERENCES account (id),
    -- Raw signed sum of the account's postings (ADR-0002); the natural sign is applied in the
    -- domain as raw × direction(type). MAY legitimately be negative (allow_negative accounts) —
    -- deliberately no CHECK here; the overdraft policy is the domain's call, made under lock.
    balance       bigint      NOT NULL,
    -- Reconciliation watermark (ADR-0002): incremented in the same UPDATE as the balance and
    -- compared against COUNT(*) by M6. It only ever grows — postings are never deleted (I3).
    posting_count bigint      NOT NULL
                              CONSTRAINT account_balance_count_nonneg CHECK (posting_count >= 0),
    -- Written by the same UPDATE, from the injected Clock; NO DEFAULT now(), as everywhere.
    updated_at    timestamptz NOT NULL
) WITH (fillfactor = 90);
-- fillfactor 90: this is the hot-row table — every posting rewrites the balance rows it touches
-- under the FOR UPDATE serialization of ADR-0003. The free space per page keeps those updates
-- heap-only (HOT), sparing index maintenance on the busiest write path (ADR-0002).

-- INSERT: the create-account snapshot row (and this migration's backfill sibling below);
-- UPDATE: the posting bump. No DELETE — a snapshot lives exactly as long as its account, and
-- accounts are never deleted (V2).
GRANT SELECT, INSERT, UPDATE ON account_balance TO ledger_app;

DO $$
DECLARE
    granted text;
BEGIN
    SELECT string_agg(a.privilege_type, ',' ORDER BY a.privilege_type)
    INTO granted
    FROM pg_class c, aclexplode(c.relacl) a
    WHERE c.oid = 'public.account_balance'::regclass
      AND a.grantee = 'ledger_app'::regrole;
    IF granted IS DISTINCT FROM 'INSERT,SELECT,UPDATE' THEN
        RAISE EXCEPTION 'grant model not established: ledger_app grants on account_balance are [%], expected [INSERT,SELECT,UPDATE]',
            COALESCE(granted, 'none');
    END IF;
END
$$;

-- The backfill the V2 forward-contract demanded: one zero row per M1-era account. updated_at
-- mirrors the account's created_at — a migration must not read a clock (the same principle as
-- V2's no-DEFAULT-now(): ambient time below the application is invisible to the ArchUnit rule
-- and unfalsifiable in tests). A zero balance with zero postings is exact, not approximate:
-- no posting can predate this table.
INSERT INTO account_balance (account_id, balance, posting_count, updated_at)
SELECT id, 0, 0, created_at
FROM account;

-- Self-verification: no account may lack a balance row. The lock protocol takes FOR UPDATE on
-- the balance rows of every touched account (ADR-0003) — an orphan here is not a data wart, it
-- is a guaranteed runtime failure, so the migration refuses to record success over one.
DO $$
DECLARE
    orphans bigint;
BEGIN
    SELECT count(*)
    INTO orphans
    FROM account a
    LEFT JOIN account_balance b ON b.account_id = a.id
    WHERE b.account_id IS NULL;
    IF orphans <> 0 THEN
        RAISE EXCEPTION 'backfill incomplete: % account(s) have no account_balance row', orphans;
    END IF;
END
$$;
