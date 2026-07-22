-- Testcontainers bootstrap — mirrors docker/postgres/init/01-roles-and-databases.sql (minus the
-- keycloak database, which tests do not need): the SAME two-role grant model everywhere, so the
-- privilege tests prove production behavior (invariants I3 / I16).
-- Executed by withInitScript() as the container superuser against database "ledger", before the
-- Spring context (and therefore Flyway) ever connects.
-- NB: keep this file to flat single statements — Testcontainers runs it through a simple
-- semicolon-splitting script runner that cannot parse dollar-quoted blocks.

CREATE ROLE ledger_migrate LOGIN PASSWORD 'ledger_migrate';
CREATE ROLE ledger_app LOGIN PASSWORD 'ledger_app';

ALTER DATABASE ledger OWNER TO ledger_migrate;
GRANT CONNECT ON DATABASE ledger TO ledger_app;
