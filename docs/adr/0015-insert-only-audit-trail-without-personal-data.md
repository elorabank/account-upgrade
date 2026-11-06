# ADR-0015: An insert-only audit trail without personal data

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

A bank must show who did what and when, for a long time. Personal data should not be kept longer than needed.

## Decision

`audit-service` reads every business topic and writes one row per event to `AUDIT_EVENT`: actor, event type, a
short summary, time, account and correlation id. A trigger blocks UPDATE and DELETE. Summaries use codes and counts
only, and anything that looks like an email address is masked, so the trail holds no names or emails. A search API
serves auditors, ops admins and the `audit.read` scope.

## Consequences

- One account's history, or one business action across services (by correlation id), in one place.
- It grows forever: archiving old months is an open item, and must be designed around the trigger (for example by
  dropping partitions).

## Alternatives considered

- **Each service keeping its own history:** no single place to look.
- **Audit from logs:** logs are not a reliable, queryable record.
