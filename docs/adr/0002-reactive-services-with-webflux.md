# ADR-0002: Reactive services with Spring WebFlux

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

The project is a learning project, and learning reactive Spring is one of its goals. The work is mostly waiting on
I/O: the core banking system (tens to hundreds of milliseconds per call), SFTP, Kafka, mail and HTTP streams between
services. Oracle is reached through JPA and Spring Batch, which are blocking.

## Decision

All services run on Spring WebFlux (Netty). Kafka is read and written with reactor-kafka. Blocking work (JPA, JDBC,
Spring Batch, mail) never runs on an event-loop thread: it goes through `BlockingJpa` (common), which runs it on
Reactor's `boundedElastic` scheduler, or on its own threads. BlockHound fails any test that blocks an event-loop
thread.

## Consequences

- Many core-system calls in flight on few threads: 50 at once per copy turned a 20,000-account run from 1,224 s into
  39 s (see `docs/hld.md`, Performance).
- Harder code: two worlds (reactive and blocking) meet at clear boundaries, and both have to be understood.
- reactor-kafka is no longer developed by the Reactor team (1.3 is its last line). That is a risk for the Spring
  Boot 4 migration and later (see the HLD's risks).

## Alternatives considered

- **Spring MVC with Java 21 virtual threads:** plain blocking code with the same I/O concurrency, and simpler.
  Without the learning goal this would likely be the better choice. Kept in mind for a future redesign.
