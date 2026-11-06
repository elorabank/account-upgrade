# ADR-0011: "Run now" is broadcast to every copy over Kafka

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

With copies sharing a run (ADR-0010), a manual start from the console reaches only the one copy behind the request.
A normal Kafka consumer group gives each message to one member, not to all.

## Decision

The copy that receives "run now" starts the run itself and publishes `UpgradeRunRequested` on
`upgrade.run-requested` (through the outbox). Every copy reads that topic in its own consumer group,
`account-upgrade-service-<copy name>`, starting at the latest offset, so every copy hears every request; a copy
already busy with a run ignores it. The console answers 409 while any copy holds a live lease.

## Consequences

- No list of copies is needed; copies can come and go.
- Every start leaves an empty consumer group, which Kafka removes after a week.
- With secured Kafka the service needs read rights on groups with that prefix (`init-acls.sh`; checked with real
  clients on 2026-11-06).

## Alternatives considered

- **HTTP calls to every copy:** needs service discovery.
- **Redis publish/subscribe:** a copy that is briefly disconnected misses the message.
