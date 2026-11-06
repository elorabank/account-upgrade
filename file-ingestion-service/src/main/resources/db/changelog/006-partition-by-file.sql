--liquibase formatted sql

-- One partition per file (AUTOMATIC list partitioning: Oracle adds the partition of a new FILE_ID by itself), so
-- the daily clean-up drops a file's staged rows whole, and reading one file's rows reads only its partition.
-- The file-and-row index is split by file (LOCAL); the id and account indexes stay one index over the table (GLOBAL).
-- Skipped, and marked as run, on an Oracle without the partitioning option: the clean-up then deletes rows.
--changeset bank:ing-006-partition-by-file
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM V$OPTION WHERE PARAMETER = 'Partitioning' AND VALUE = 'TRUE'
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM USER_PART_TABLES WHERE TABLE_NAME = 'ACCOUNT_STAGING'
ALTER TABLE ACCOUNT_STAGING MODIFY
    PARTITION BY LIST (FILE_ID) AUTOMATIC
    (PARTITION P_NO_FILE VALUES (0))
    ONLINE
    UPDATE INDEXES (
        PK_ACCOUNT_STAGING  GLOBAL,
        UQ_STAGING_FILE_ROW LOCAL,
        IX_STAGING_ACCOUNT  GLOBAL);
