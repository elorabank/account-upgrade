# ADR-0003: One service per business step, one Oracle schema each

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

The nightly upgrade is a pipeline of steps with different load, failure and scaling patterns: loading a file,
upgrading accounts against the core system, emailing customers, and keeping an audit trail. Each step's data is its
own.

## Decision

Four services, one per step (`file-ingestion-service`, `account-upgrade-service`, `communication-service`,
`audit-service`), plus `api-gateway`, which serves the ops console (`ops-ui`). Each service owns one Oracle schema
(`ING_OWNER`, `UPG_OWNER`, `COM_OWNER`, `AUD_OWNER`) in one shared Oracle instance and creates it with its own
Liquibase changelog. No service reads another's tables; data crosses only through Kafka events (ADR-0004) or an API
(ADR-0007). Shared code lives in the `common` library, not in a shared service.

## Consequences

- Each step scales and fails on its own: several upgrade copies (ADR-0010), and a mail outage never stops upgrades.
- Consistency between services is eventual, through events.
- More to run and watch than a single application.
- The single Oracle instance is shared capacity and a shared point of failure; separate instances would cost more.

## Alternatives considered

- **A modular monolith:** one deployable with the same module boundaries; simpler to run, fewer network hops.
  Rejected because learning microservice patterns is part of the project's purpose.
- **One database per service:** stronger isolation, higher cost; not needed at this size.
