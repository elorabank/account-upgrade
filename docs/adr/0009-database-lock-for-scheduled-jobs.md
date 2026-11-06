# ADR-0009: Scheduled jobs run once at a time under a database lock (ShedLock)

- **Status:** Accepted for the load; superseded by ADR-0010 for the upgrade run
- **Date:** 2026-11-05

## Context

Every service may run as several instances, and a scheduled job such as the 01:00 load must run once at a time.

## Decision

ShedLock with a JDBC lock table (`SHEDLOCK`, using the database clock). "Run now" from the console takes the same
lock (`ManualRunner`) and answers 409 while the job runs anywhere.

## Consequences

- Simple, and enough for the load: one file at a time is fine.
- For the upgrade run it left every copy but one idle; replaced there by leases (ADR-0010) on 2026-11-06.
