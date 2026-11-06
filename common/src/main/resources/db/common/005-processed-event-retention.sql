--liquibase formatted sql

-- The daily clean-up deletes PROCESSED_EVENT rows by age (RetentionAutoConfiguration); without an index on the
-- date every batch would scan the whole table.
--changeset bank:common-005-processed-event-retention
CREATE INDEX IX_PROCESSED_AT ON PROCESSED_EVENT (PROCESSED_AT);
