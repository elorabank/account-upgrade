--liquibase formatted sql

-- UPGRADE_REQUEST_ID: one letter per upgrade, even if the same upgrade is announced twice.
-- NEXT_ATTEMPT_AT: when a PENDING letter is next due to be sent (null = now), for retries with backoff.
--changeset bank:com-002-letter-delivery
ALTER TABLE WELCOME_LETTER ADD (UPGRADE_REQUEST_ID NUMBER(19) NOT NULL, NEXT_ATTEMPT_AT TIMESTAMP WITH TIME ZONE);
ALTER TABLE WELCOME_LETTER ADD CONSTRAINT UQ_LETTER_UPGRADE_REQUEST UNIQUE (UPGRADE_REQUEST_ID);
DROP INDEX IX_LETTER_STATUS;
CREATE INDEX IX_LETTER_DUE ON WELCOME_LETTER (STATUS, NEXT_ATTEMPT_AT);
