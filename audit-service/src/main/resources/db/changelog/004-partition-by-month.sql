--liquibase formatted sql

-- One partition per month an event happened, so a whole old month can be moved to an archive file and dropped
-- (AuditArchiver). EVENT_DAY is the UTC date-time of OCCURRED_AT, a virtual column (Oracle cannot partition by month
-- on a TIMESTAMP WITH TIME ZONE). The time index is split by month (LOCAL); the others stay one index over the
-- table (GLOBAL), because an event id or an account's history spans every month.
-- Dropping a partition is DDL, so the insert-only trigger does not fire; nothing else can remove audit rows.
-- Skipped, and marked as run, on an Oracle without the partitioning option: the trail then stays in the table.
--changeset bank:aud-004-event-day
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM V$OPTION WHERE PARAMETER = 'Partitioning' AND VALUE = 'TRUE'
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'AUDIT_EVENT' AND COLUMN_NAME = 'EVENT_DAY'
ALTER TABLE AUDIT_EVENT ADD (EVENT_DAY DATE GENERATED ALWAYS AS (CAST(SYS_EXTRACT_UTC(OCCURRED_AT) AS DATE)) VIRTUAL);

--changeset bank:aud-004-partition-by-month
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'AUDIT_EVENT' AND COLUMN_NAME = 'EVENT_DAY'
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM USER_PART_TABLES WHERE TABLE_NAME = 'AUDIT_EVENT'
ALTER TABLE AUDIT_EVENT MODIFY
    PARTITION BY RANGE (EVENT_DAY) INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
    (PARTITION P_BEFORE_2020 VALUES LESS THAN (DATE '2020-01-01'))
    ONLINE
    UPDATE INDEXES (
        PK_AUDIT_EVENT       GLOBAL,
        UQ_AUDIT_EVENT_ID    GLOBAL,
        IX_AUDIT_ACCOUNT     GLOBAL,
        IX_AUDIT_CORRELATION GLOBAL,
        IX_AUDIT_OCCURRED    LOCAL);
