# ADR-0021: Partition the big tables; the clean-up drops whole partitions

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

The daily clean-up (ADR-0019) deletes old rows in batches. At a million accounts a day that is millions of deleted
rows a day per table: undo and redo for each one, index maintenance, and space that is only reused, not returned.
The audit trail cannot be cleaned that way at all (a trigger blocks DELETE, ADR-0015), yet must not stay in the
table forever.

## Decision

Liquibase changesets partition the four big tables, online, with partitions Oracle adds by itself:
`UPGRADE_REQUEST` by month of business date, `WELCOME_LETTER` by month written and `AUDIT_EVENT` by month the event
happened (both through a virtual UTC-date column, because Oracle cannot partition by month on a TIMESTAMP WITH TIME
ZONE), and `ACCOUNT_STAGING` one partition per file (automatic list). Indexes that contain the partition key are split
with it (LOCAL); the others stay one index over the table (GLOBAL), because their lookups span every partition.

A retention rule can now drop partitions first (`PartitionDrop`): a month is dropped once it ended before the
rule's cutoff and none of its rows must stay (a pending request or letter keeps its month); a file's staging
partition once the file completed more than 30 days ago. Drops keep the global indexes usable. The row deletes then
take what is left, so a month held back by one pending row is still cleaned, and the partition goes once nothing in
it is needed. The first partition of each table is never dropped.

The changesets are skipped (marked as run) on an Oracle without the partitioning option, such as Standard Edition;
the clean-up then deletes rows as before.

## Consequences

- An old month goes in one quick statement, with almost no undo or redo, and its space is returned at once.
- Reading one file's staged rows reads only that file's partition.
- The audit trail can leave the table month by month without ever deleting a row (ADR-0022).
- Partitioning is a paid option of Oracle Enterprise Edition (included in Oracle Free). Without it everything still
  works, only slower to clean.
- Lookups through a global index (by id, account, lease) are unchanged; queries by month on the local ones read one
  partition.
- The check that a partition holds nothing to keep and the drop are two statements; a row that changed in between,
  in a partition this old, would be lost. Nothing writes to such old data.

## Alternatives considered

- **Daily partitions:** finer than retention needs, and hundreds of partitions per table per year.
- **Partition by reference** (letters following their upgrade request): the tables are in different schemas, on
  purpose (ADR-0003).
- **Keep batched deletes only:** simple, but the cost grows with the volume and the audit trail is left unsolved.
