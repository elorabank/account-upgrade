--liquibase formatted sql

-- The daily clean-up deletes SENT and FAILED letters by age (CommunicationConfig); this index finds them without
-- reading every letter.
--changeset bank:com-004-letter-retention
CREATE INDEX IX_LETTER_STATUS_GENERATED ON WELCOME_LETTER (STATUS, GENERATED_AT);
