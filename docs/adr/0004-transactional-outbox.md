# ADR-0004: Every event goes through a transactional outbox

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

A business change and the event announcing it must happen together. Writing to the database and then to Kafka loses
the event if the service stops in between; writing to Kafka first announces changes that may then roll back.

## Decision

A service writes each event into its own `OUTBOX_EVENT` table in the same transaction as the business change
(`OutboxService.publish`, which refuses to run outside a transaction). `OutboxPoller` sends waiting events in id
order with `acks=all` and an idempotent producer, then marks them published; published events are purged after 7
days. Locked rows are skipped (`FOR UPDATE SKIP LOCKED`), so several instances can poll safely. Every event has an
envelope: event id, type, correlation id, actor, time, data.

## Consequences

- No event is lost and none announces a change that rolled back.
- Delivery is at least once: after a crash an event can be sent twice, so consumers de-duplicate (ADR-0005).
- Events reach Kafka about a second after the commit (the poll interval).
- `bank.outbox.waiting` and `bank.outbox.oldest.age` show a backlog; the `OutboxFallingBehind` alert fires on one.

## Alternatives considered

- **Send to Kafka after the commit:** loses events when the service stops in between.
- **Distributed (XA) transactions between Oracle and Kafka:** not supported in practice.
- **Change data capture (Debezium) on the outbox table:** no polling delay, but more infrastructure to run.
