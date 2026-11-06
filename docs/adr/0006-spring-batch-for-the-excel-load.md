# ADR-0006: Spring Batch for the Excel load

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

The nightly file can hold up to about a million rows (Excel's limit is 1,048,576). A stop half way must neither lose
rows nor load them twice, and one bad row must not stop the file.

## Decision

Each file is one Spring Batch job instance (its `fileId` is the only identifying parameter). A streaming reader reads
the first sheet row by row, finding columns by header name; a processor validates each row; a writer saves valid
rows to `ACCOUNT_STAGING` and bad ones to `INGESTION_ERROR`, chunk by chunk, with the reader's position in the same
transaction. A failed file is restarted from its last committed chunk. The file's SHA-256 checksum stops the same
content from being loaded twice. Files move to `processed` or `error` on SFTP.

## Consequences

- Restartable and duplicate-free without our own checkpoint code.
- Spring Batch needs its own tables (`BATCH_*`); records of completed loads are deleted after 30 days (ADR-0019).
- Spring Batch is blocking and is reworked in its version 6 (Spring Boot 4): part of that migration.

## Alternatives considered

- **A home-made loader with its own checkpoints:** more code to get right.
- **Reading the whole workbook into memory:** too big at a million rows.
