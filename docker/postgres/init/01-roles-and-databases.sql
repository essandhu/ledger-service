-- Runs once on first container start (docker-entrypoint-initdb.d), as superuser, in DB "ledger".
-- Dev/demo credentials only. Testcontainers integration tests create the same roles in their
-- own bootstrap so the grant model is identical everywhere (invariant I3 / I16).

-- Schema owner: runs Flyway migrations, owns all objects.
CREATE ROLE ledger_migrate LOGIN PASSWORD 'ledger_migrate';

-- Runtime role: least privilege. Migrations grant SELECT/INSERT (and, where appropriate, UPDATE
-- on mutable tables like account/account_balance) — but never UPDATE/DELETE on journal_entry
-- or posting. That absence is the DB-level immutability guarantee.
CREATE ROLE ledger_app LOGIN PASSWORD 'ledger_app';

ALTER DATABASE ledger OWNER TO ledger_migrate;
GRANT CONNECT ON DATABASE ledger TO ledger_app;

-- Keycloak gets its own database in the same instance (dev/demo convenience).
CREATE ROLE keycloak LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;
