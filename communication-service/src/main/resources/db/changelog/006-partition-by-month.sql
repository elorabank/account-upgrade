--liquibase formatted sql

-- One partition per month a letter was written, so the daily clean-up drops an old month whole. Oracle cannot
-- partition by month on a TIMESTAMP WITH TIME ZONE, so the key is GENERATED_DAY, the UTC date-time of GENERATED_AT:
-- a virtual column, computed, never stored. The status-and-age index is split by month (LOCAL); the others stay one
-- index over the whole table (GLOBAL), because their lookups (by id, upgrade, account, due time) span every month.
-- Skipped, and marked as run, on an Oracle without the partitioning option: the clean-up then deletes rows.
--changeset bank:com-006-generated-day
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM V$OPTION WHERE PARAMETER = 'Partitioning' AND VALUE = 'TRUE'
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'WELCOME_LETTER' AND COLUMN_NAME = 'GENERATED_DAY'
ALTER TABLE WELCOME_LETTER ADD (GENERATED_DAY DATE GENERATED ALWAYS AS (CAST(SYS_EXTRACT_UTC(GENERATED_AT) AS DATE)) VIRTUAL);

--changeset bank:com-006-partition-by-month
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'WELCOME_LETTER' AND COLUMN_NAME = 'GENERATED_DAY'
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM USER_PART_TABLES WHERE TABLE_NAME = 'WELCOME_LETTER'
ALTER TABLE WELCOME_LETTER MODIFY
    PARTITION BY RANGE (GENERATED_DAY) INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
    (PARTITION P_BEFORE_2020 VALUES LESS THAN (DATE '2020-01-01'))
    ONLINE
    UPDATE INDEXES (
        PK_WELCOME_LETTER          GLOBAL,
        UQ_LETTER_UPGRADE_REQUEST  GLOBAL,
        IX_LETTER_ACCOUNT          GLOBAL,
        IX_LETTER_DUE              GLOBAL,
        IX_LETTER_STATUS_GENERATED LOCAL);
