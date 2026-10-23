# ADR-011: JDBC (JdbcClient) instead of JPA for the R1 backend

**Status:** Accepted, October 2026. **Changes:** Doc 05 §4 named Spring Data JPA.

## Context

Most R1 rules live in PostgreSQL: row-level security on Finance data, append-only and locked tables, the
allocation checks (ICCP-FIN-001…008) and the hash-chained audit trail. Every transaction must first set
`iccp.actor`, `iccp.staff_id` and `iccp.fin_scope` so those rules know who is acting. Finance imports must
apply each row in its own savepoint so one bad row is held for checking without losing the rest.

## Decision

Use Spring's `JdbcClient` with hand-written SQL. A custom transaction manager (`DbSessionTransactionManager`)
sets the three session values at the start of every transaction. Imports use nested transactions (savepoints).

## Consequences

* The SQL that runs is the SQL in the code, which makes the RLS and trigger behaviour easy to reason about and test.
* No entity caching: a value changed by a trigger is always read fresh.
* More code for simple CRUD. Accepted because R1 has few simple CRUD screens.
* Read-only transactions are not used where the audit trail is written, because PostgreSQL rejects inserts in them.
