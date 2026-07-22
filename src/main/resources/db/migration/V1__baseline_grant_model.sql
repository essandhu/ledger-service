-- I16 baseline (M0): establish the two-role grant model in schema "public".
--
-- Roles are deliberately NOT created here: Flyway runs as ledger_migrate, which lacks CREATEROLE
-- (least privilege). Role creation is environment bootstrap — docker/postgres/init for the
-- compose stack, src/test/resources/db/testsupport for Testcontainers — and this migration fails
-- fast if that contract was not honored: the runtime role must exist, and ledger_migrate must own
-- the database. The ownership check matters because it is what makes the REVOKE/GRANT below legal
-- (via pg_database_owner); without it they degrade to WARNINGs and silently grant nothing.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
        RAISE EXCEPTION 'bootstrap contract violation: runtime role ledger_app does not exist';
    END IF;
    IF NOT pg_has_role(current_user, 'pg_database_owner', 'USAGE') THEN
        RAISE EXCEPTION 'bootstrap contract violation: % does not own database %',
            current_user, current_database();
    END IF;
END
$$;

-- Runtime role may resolve objects in the schema but never create its own. PostgreSQL 15+
-- already denies CREATE on "public" to PUBLIC; granting/revoking explicitly makes the model
-- hold on any instance (e.g. a restored legacy database) and survive default changes.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ledger_app;

-- Self-verification against the ACL itself: a direct USAGE entry for ledger_app must exist and
-- PUBLIC must hold no CREATE. Effective-privilege functions (has_schema_privilege) cannot tell a
-- direct grant from PUBLIC inheritance, so the ACL is inspected — a silent no-op can never be
-- recorded as migration success.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM pg_namespace n, aclexplode(n.nspacl) a
                   WHERE n.nspname = 'public'
                     AND a.grantee = 'ledger_app'::regrole
                     AND a.privilege_type = 'USAGE') THEN
        RAISE EXCEPTION 'grant model not established: no direct USAGE grant for ledger_app on schema public';
    END IF;
    IF EXISTS (SELECT 1
               FROM pg_namespace n, aclexplode(n.nspacl) a
               WHERE n.nspname = 'public'
                 AND a.grantee = 0  -- grantee 0 = PUBLIC
                 AND a.privilege_type = 'CREATE') THEN
        RAISE EXCEPTION 'grant model not established: PUBLIC still holds CREATE on schema public';
    END IF;
END
$$;

-- No extensions required in v1: identifiers are UUIDv7 generated application-side (PLAN §4.3),
-- so no pgcrypto/uuid-ossp. This migration is the designated home if that ever changes.
