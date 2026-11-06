--liquibase formatted sql

-- One partition per month of BUSINESS_DATE, so the daily clean-up drops an old month whole instead of deleting its
-- rows one by one. Oracle adds the partition of a new month by itself (INTERVAL). ONLINE: the table stays readable
-- and writable while it is converted. Indexes whose columns include the business date are split by month (LOCAL);
-- the others, such as the lease and run lookups, stay one index over the whole table (GLOBAL).
-- Skipped, and marked as run, on an Oracle without the partitioning option (Standard Edition): the clean-up then
-- deletes rows, as before. Also skipped if the table is already partitioned, for example by a DBA.
--changeset bank:upg-006-partition-by-month
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM V$OPTION WHERE PARAMETER = 'Partitioning' AND VALUE = 'TRUE'
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM USER_PART_TABLES WHERE TABLE_NAME = 'UPGRADE_REQUEST'
ALTER TABLE UPGRADE_REQUEST MODIFY
    PARTITION BY RANGE (BUSINESS_DATE) INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
    (PARTITION P_BEFORE_2020 VALUES LESS THAN (DATE '2020-01-01'))
    ONLINE
    UPDATE INDEXES (
        PK_UPGRADE_REQUEST      GLOBAL,
        UQ_UPGRADE_ACCOUNT_DATE LOCAL,
        IX_UPGRADE_STATUS_DATE  LOCAL,
        IX_UPGRADE_RUN          GLOBAL,
        IX_UPGRADE_CLAIM        GLOBAL);
