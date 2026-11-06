--liquibase formatted sql

-- The upgrade run no longer takes a cluster lock (every copy joins it), so this service has no use for SHEDLOCK.
--changeset bank:upg-005-drop-shedlock
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM user_tables WHERE table_name = 'SHEDLOCK'
DROP TABLE SHEDLOCK PURGE;
