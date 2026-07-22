-- I16 (M1): the account table (PLAN §4.3) and least-privilege grants for the runtime role.
--
-- M2 forward-contract (recorded here so the landmine is visible before it is armed): V3 creates
-- account_balance and MUST backfill one zero-balance row per already-existing account
-- (INSERT ... SELECT id, 0, 0, ... FROM account) — the posting engine locks the balance row of
-- every touched account (PLAN §6), so an account without one would break the lock protocol.
-- From M2 on, the create-account use case inserts the snapshot row in the same transaction.

CREATE TABLE account (
    id             uuid        PRIMARY KEY,
    -- Domain validates first; the CHECKs are defense in depth against non-API writes, and
    -- mirror the domain rules exactly (any-whitespace blank, not just spaces; no control
    -- characters) so a row a DBA sneaks in can never be one the domain refuses to load.
    name           text        NOT NULL
                               CONSTRAINT account_name_not_blank CHECK (name ~ '\S')
                               CONSTRAINT account_name_no_control_chars CHECK (name !~ '[[:cntrl:]]')
                               CONSTRAINT account_name_max_length CHECK (char_length(name) <= 200),
    -- text + CHECK rather than the ERD's literal char(3): bpchar space-padding semantics are a
    -- classic wart and fight Hibernate schema validation; the CHECK preserves the exact-3-chars
    -- invariant without them. ISO-4217 membership is enforced by the domain (CurrencyCode).
    currency       text        NOT NULL
                               CONSTRAINT account_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    type           text        NOT NULL
                               CONSTRAINT account_type_valid CHECK
                               (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    status         text        NOT NULL
                               CONSTRAINT account_status_valid CHECK
                               (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    allow_negative boolean     NOT NULL,
    -- Optimistic guard for concurrent metadata edits (PLAN §4.3); posting races use the M2
    -- balance lock, never this column.
    version        bigint      NOT NULL,
    -- Deliberately NO DEFAULT now() on the timestamps: a database default would silently mask a
    -- missing Clock wire in the application and reintroduce ambient time below the visibility
    -- of the ArchUnit no-ambient-time rule (TEST-STRATEGY §1).
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

-- Accounts are created and edited (rename, lifecycle status) but never deleted; the absent
-- DELETE grant is the database-level half of that guarantee (the API simply has no delete).
GRANT SELECT, INSERT, UPDATE ON account TO ledger_app;

-- Self-verification (the V1 pattern, I16): the ACL must record EXACTLY {SELECT, INSERT, UPDATE}
-- for ledger_app. An exact set — not DELETE-absence — so any privilege creep (DELETE, TRUNCATE,
-- TRIGGER, REFERENCES, ...) fails the migration loudly in every environment the migration runs
-- in, not only where the CI test suite runs.
DO $$
DECLARE
    granted text;
BEGIN
    SELECT string_agg(a.privilege_type, ',' ORDER BY a.privilege_type)
    INTO granted
    FROM pg_class c, aclexplode(c.relacl) a
    WHERE c.oid = 'public.account'::regclass
      AND a.grantee = 'ledger_app'::regrole;
    IF granted IS DISTINCT FROM 'INSERT,SELECT,UPDATE' THEN
        RAISE EXCEPTION 'grant model not established: ledger_app grants on account are [%], expected [INSERT,SELECT,UPDATE]',
            COALESCE(granted, 'none');
    END IF;
END
$$;
