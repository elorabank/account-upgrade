--liquibase formatted sql

-- The ingestion service consumes no events, so it has no use for the shared PROCESSED_EVENT table.
--changeset bank:ing-005-drop-processed-event
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM user_tables WHERE table_name = 'PROCESSED_EVENT'
DROP TABLE PROCESSED_EVENT PURGE;
