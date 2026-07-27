-- I8/I9 (M4): the idempotency bookkeeping table (ADR-0004). One row per SUCCESSFUL
-- keyed write, inserted in the SAME transaction as the journal entry it records — so there is
-- no crash window in which the effect exists without its record or vice versa (ADR-0004
-- §Decision, reason 1). Rejected postings write nothing here (only successful outcomes are
-- recorded), so a retry after a rejection legitimately re-executes.
--
-- Division of labor with V3: the PERMANENT double-post guard is V3's partial unique index
-- journal_entry_idem_backstop (created_by, idempotency_key) — it lives on the entry, which is
-- kept forever. This table is the replay/conflict DIAGNOSTICS: request hash for conflict
-- detection, stored response for byte-identical replay. Purging it (designed, disabled — see
-- the DELETE grant below) can degrade only diagnostics, never safety (ADR-0004, option 3b).
--
-- ADRs in force: ADR-0004 (scope, hash, retention), ADR-0003 (the posting transaction this
-- insert joins), ADR-0001 (the hashed canonical form carries integer minor units).

CREATE TABLE idempotency_record (
    -- The JWT subject: one half of the (principal, key) scope (ADR-0004 option 1b). Matches
    -- journal_entry.created_by, which the backstop index pairs with the same key.
    created_by      text        NOT NULL,
    -- Client-supplied Idempotency-Key, verbatim. Shape rules mirror the application guard
    -- (InvalidIdempotencyKey): non-blank, no control characters, bounded length — defense in
    -- depth against non-API writes, the V2/V3 rationale.
    idem_key        text        NOT NULL
                                CONSTRAINT idempotency_record_key_not_blank
                                CHECK (idem_key ~ '\S')
                                CONSTRAINT idempotency_record_key_no_control_chars
                                CHECK (idem_key !~ '[[:cntrl:]]')
                                -- No commas: HTTP joins duplicate header fields with commas
                                -- (RFC 9110), so a comma-bearing key is indistinguishable
                                -- from an accidentally doubled header — rejected loudly at
                                -- the guard, refused here as defense in depth.
                                CONSTRAINT idempotency_record_key_no_comma
                                CHECK (idem_key !~ ',')
                                CONSTRAINT idempotency_record_key_max_length
                                CHECK (char_length(idem_key) <= 200),
    -- SHA-256 over the frozen canonical command form (ADR-0004 option 2c), lowercase hex.
    -- text + CHECK rather than char(64): the V2/V3 shape-rule house style, and what the JPA
    -- String mapping validates against.
    request_hash    text        NOT NULL
                                CONSTRAINT idempotency_record_hash_shape
                                CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- The entry this key produced: the permanent audit answer to "which request created this".
    entry_id        uuid        NOT NULL REFERENCES journal_entry (id),
    -- The original response, for replays. Status is audit data (replays always answer 200 per
    -- the API contract); the body is returned VERBATIM. Deliberate deviation from the schema
    -- sketch's jsonb, recorded at M4: jsonb re-serialization normalizes key order and whitespace,
    -- which would break the byte-identical replay the ADR promises — text preserves the exact bytes,
    -- and the IS JSON OBJECT CHECK keeps validity enforcement (every stored body is an
    -- EntryResponse document — an OBJECT, so the check mirrors the writer exactly, the V2
    -- rule: a row a DBA sneaks in can never be one the replay path would serve as garbage).
    response_status int         NOT NULL,
    response_body   text        NOT NULL
                                CONSTRAINT idempotency_record_body_is_json
                                CHECK (response_body IS JSON OBJECT),
    -- Written from the injected Clock (the posting's own postedAt instant — one transaction,
    -- one time); NO DEFAULT now(), as everywhere (the V2 principle: ambient time below the
    -- application is invisible to the ArchUnit rule and unfalsifiable in tests).
    created_at      timestamptz NOT NULL,
    -- created_at + configurable TTL (ledger.idempotency.ttl, default 90 days). Populated from
    -- birth so enabling the purge is a configuration change, not a migration (ADR-0004).
    expires_at      timestamptz NOT NULL,
    -- The (principal, key) scope itself: one record per scope at rest. The RACE arbiter is
    -- V3's backstop index on journal_entry, in every interleaving — the entry insert reaches
    -- the database first (flushed by the balance-bump statement), before this table's insert
    -- ever leaves the persistence context — so this PK is the at-rest uniqueness guarantee,
    -- not the arbitration point.
    CONSTRAINT idempotency_record_pkey PRIMARY KEY (created_by, idem_key)
);

-- The purge scan path: DELETE... WHERE expires_at < now, batched by ctid.
CREATE INDEX idempotency_record_expiry ON idempotency_record (expires_at);

-- SELECT: replay/conflict lookups. INSERT: the same-transaction record write. DELETE: the
-- ADR-0004 purge path — designed and shipped disabled (ledger.idempotency.purge.enabled=false);
-- granting it from birth is what makes enabling the purge a configuration change, not a
-- migration. Deliberately the first DELETE grant in the schema: this table is diagnostics with
-- a designed expiry, not ledger history — the money guarantee lives on journal_entry, which
-- remains SELECT/INSERT-only (I3). No UPDATE: records are written exactly once.
GRANT SELECT, INSERT, DELETE ON idempotency_record TO ledger_app;

-- Self-verification (the V1/V2/V3 pattern, I16): the ACL must record EXACTLY
-- {SELECT, INSERT, DELETE} for ledger_app, so any privilege creep fails the migration loudly
-- in every environment it runs in.
DO $$
DECLARE
    granted text;
BEGIN
    SELECT string_agg(a.privilege_type, ',' ORDER BY a.privilege_type)
    INTO granted
    FROM pg_class c, aclexplode(c.relacl) a
    WHERE c.oid = 'public.idempotency_record'::regclass
      AND a.grantee = 'ledger_app'::regrole;
    IF granted IS DISTINCT FROM 'DELETE,INSERT,SELECT' THEN
        RAISE EXCEPTION 'grant model not established: ledger_app grants on idempotency_record are [%], expected [DELETE,INSERT,SELECT]',
            COALESCE(granted, 'none');
    END IF;
END
$$;

-- M4 arms journal_entry.idempotency_key (NULL since V3): mirror the key shape rules there as
-- defense in depth, exactly as idem_key above. NULL rows — every pre-M4 entry — pass by SQL
-- three-valued logic, the V3 description-CHECK precedent.
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_idem_key_not_blank
        CHECK (idempotency_key ~ '\S'),
    ADD CONSTRAINT journal_entry_idem_key_no_control_chars
        CHECK (idempotency_key !~ '[[:cntrl:]]'),
    ADD CONSTRAINT journal_entry_idem_key_no_comma
        CHECK (idempotency_key !~ ','),
    ADD CONSTRAINT journal_entry_idem_key_max_length
        CHECK (char_length(idempotency_key) <= 200);
