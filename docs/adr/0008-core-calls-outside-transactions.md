# ADR-0008: Core-system calls outside transactions, with idempotency keys

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

The core banking system is remote, can be slow, and can be down. Holding a database transaction (and its locks) open
during a remote call does not scale, and the service can stop between a successful call and recording it.

## Decision

A request is claimed in a short transaction, the core system is called outside any transaction, and the outcome is
recorded with its event in another one. Every call carries the idempotency key `<account>:<businessDate>`, so a
repeated call is harmless. A refusal fails the account at once. "Unavailable" is retried 3 times within the run;
then the account waits for the next run and becomes FAILED after 3 runs. When 5 accounts in a row cannot be
reached, the run stops, so an outage does not use up every account's attempts.

## Consequences

- Short transactions and locks however slow the core system is.
- Calls are at least once: the core system must honour the idempotency key. To confirm with the core team when the
  real adapter is built (the adapter is still simulated).

## Alternatives considered

- **Calling inside the transaction:** long locks, and still no guarantee across a crash.
