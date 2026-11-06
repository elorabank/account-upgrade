# ADR-0007: Planning pulls a loaded file's accounts over an internal HTTP stream

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

After a load, the upgrade service needs every account of the file. The accounts belong to the ingestion service
(ADR-0003). Kafka events should stay small and about one thing.

## Decision

The `FileLoaded` event carries only the file's id, date and counts. The upgrade service then streams the accounts
from `GET /internal/files/{id}/accounts` (newline-delimited JSON, read from the database page by page) with its own
client-credentials token (scope `staging.read`), and saves them in chunks of 500. The next chunk is requested only
when the previous one is saved, so the whole chain runs at the speed of the slowest step. `/internal/**` accepts
service scopes only and is not routed by the gateway.

## Consequences

- Kafka carries one small event per file instead of a million account messages.
- Planning needs the ingestion service to be up; when it is not, the event is retried with backoff.
- Staging rows must outlive planning; they are kept 30 days (ADR-0019).

## Alternatives considered

- **One Kafka event per account:** a million messages per file, with ordering and duplicates per account to handle.
- **Reading the ingestion tables directly:** breaks data ownership (ADR-0003).
