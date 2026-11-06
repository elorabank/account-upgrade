# ADR-0019: Daily retention with batched deletes

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

At a million accounts a day the tables grow by several gigabytes a day; Oracle Free stores at most 12 GB. Deleting
millions of rows in one statement holds locks and fills Oracle's undo space.

## Decision

Every service with a database runs a clean-up daily at 05:30 in the bank's time zone, after the nightly pipeline.
Each service declares `RetentionRule` beans; `RetentionRunner` deletes in batches of 10,000 rows, each in its own
transaction, and a failing rule does not stop the others. Default periods: staging rows and Spring Batch history of
completed loads 30 days, processed-event records 30 days, finished upgrade requests and letters 400 days. Pending
work, failed loads, the file list and the audit trail are never deleted. Each period can be changed per rule.

## Consequences

- Tables stop growing without limit; checked against Oracle with rows dated 2020 (only old, finished rows went).
- The periods are a starting point: the bank's compliance team decides how long records must be kept.
- Deleting rows still costs work; partitioning by day or month (open item) would let whole days be dropped at once.

## Alternatives considered

- **Partitioning now:** the better long-term answer, but more design; planned next.
- **A database job (DBMS_SCHEDULER):** keeps the rules out of the code and out of the tests.
