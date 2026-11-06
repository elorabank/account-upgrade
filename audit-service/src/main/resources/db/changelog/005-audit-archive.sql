--liquibase formatted sql

-- One row per month moved out of AUDIT_EVENT into an archive file: where it went and how to check it.
-- WRITING: a copy of the service is writing the file (STARTED_AT); ARCHIVED: the file is stored and was read back
-- and checked; DROPPED: the month's partition is gone, the file is now the only copy.
--changeset bank:aud-005-archive
CREATE TABLE AUDIT_ARCHIVE (
    MONTH       DATE               NOT NULL,
    STATUS      VARCHAR2(20 CHAR)  NOT NULL,
    FILE_NAME   VARCHAR2(255 CHAR),
    ROW_COUNT   NUMBER(12),
    SHA256      VARCHAR2(64 CHAR),
    FIRST_ID    NUMBER(19),
    LAST_ID     NUMBER(19),
    STARTED_AT  TIMESTAMP WITH TIME ZONE NOT NULL,
    ARCHIVED_AT TIMESTAMP WITH TIME ZONE,
    DROPPED_AT  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_AUDIT_ARCHIVE PRIMARY KEY (MONTH),
    CONSTRAINT CK_ARCHIVE_STATUS CHECK (STATUS IN ('WRITING', 'ARCHIVED', 'DROPPED'))
);
