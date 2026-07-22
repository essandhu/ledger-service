# Flyway migrations

Naming: `V<version>__<snake_case_description>.sql` (e.g. `V1__baseline_roles_and_extensions.sql`).

Rules for this repo:

- SQL-first; no Java migrations.
- Migrations are **immutable once merged** — Flyway checksum validation is part of invariant I16.
- Runtime-role grants (`ledger_app`: `SELECT, INSERT` only on `journal_entry`/`posting` — the
  immutability invariant I3) are applied here, in versioned migrations, not by hand.
- The first migration lands with the walking-skeleton milestone; this directory being empty is
  expected until then.
