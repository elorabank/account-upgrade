# ADR-0005: Idempotent consumers, retries and dead-letter topics

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

Events arrive at least once (ADR-0004), and Kafka redelivers after a crash. A handler can fail for a moment (database
busy) or for good (bad data). One bad event must not stop the rest.

## Decision

Services consume through the shared `EventConsumer`:
- Partitions are handled in parallel, each in order. Events are keyed by what they are about (usually the account),
  so everything about one account stays in order.
- Up to 100 events of a partition are handled in one transaction, together with a `PROCESSED_EVENT` row per event;
  already processed events are skipped. Kafka offsets are acknowledged only after the commit.
- When a batch fails, its events are retried one by one with backoff (4 attempts, 0.5 s up to 10 s). What still
  fails is parked on `<topic>.DLT` with the reason in headers. An unreadable message, or a
  `NonRetryableEventException`, goes there at once.

## Consequences

- Each event takes effect once, even when delivered several times.
- Parked events need a person: the `EventDeadLettered` alert fires on every one; fix the cause, then replay.
- `PROCESSED_EVENT` grows with every event; it is kept 30 days, longer than Kafka keeps messages (ADR-0019).
- Natural keys back this up where they exist (for example one letter per upgrade, a unique column).

## Alternatives considered

- **Kafka exactly-once transactions:** only cover Kafka-to-Kafka work, not writes to Oracle.
- **Natural keys only:** not every handler has one.
