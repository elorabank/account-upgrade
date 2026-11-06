# ADR-0022: Old audit months move to checked archive files

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

The audit trail is kept forever and its table is insert-only (ADR-0015). Searching it in the console is needed for
recent months; years-old months are rarely read, but must still exist and be shown to be complete.

## Decision

Once a day, the audit service writes each month of `AUDIT_EVENT` that ended more than 400 days ago to one file:
gzip-compressed JSON lines, one event per line, in id order, every column (`audit-events-2025-01.jsonl.gz`). The file
goes to an `ArchiveStore` (today a folder, a durable volume in real environments), is read back from there, and is
checked: every line parses, the number of events matches the month's rows, the SHA-256 matches what was written.
`AUDIT_ARCHIVE` records the month, the file, the number of events, the SHA-256 and the id range. Only then is the
month's partition dropped (ADR-0021): DDL, so the insert-only trigger is not involved and no row is ever deleted.

The `AUDIT_ARCHIVE` row is also the claim, so two copies of the service never write the same month; a claim left
for an hour by a copy that stopped is taken over. Any failure leaves the rows in the table for the next day. If an
archived month's rows change (an old event replayed), nothing is dropped; the job reports it (metric and alert) for
a person to decide. `GET /api/audit/archives` lists the archived months with their checksums.

## Consequences

- The table holds about 13 months; older months cost a few megabytes of compressed files each.
- Old months are no longer searchable in the console; finding one account's old history means reading a file
  (`gunzip` and `jq`), or loading the month back into a table.
- The files are now the only copy of those months, so where they are kept matters: durable, backed up, and ideally
  write-once (for example a bucket with a retention lock). The checksum in `AUDIT_ARCHIVE` shows a file is unchanged.
- Without Oracle's partitioning option there are no months to drop and the whole trail stays in the table.

## Alternatives considered

- **Keep everything in the table:** simplest; the table and its indexes grow forever.
- **Move old rows to an archive table:** still in the database, and it would need deletes the trigger forbids.
- **Export with Oracle Data Pump:** a database tool outside the services, with a format only Oracle reads back.
