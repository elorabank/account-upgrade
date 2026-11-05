--liquibase formatted sql

-- The upgrade service now reads staged accounts through GET /internal/files/{id}/accounts instead of the table,
-- so the read grant from the earlier changeset ing-002 is taken back where it exists.
--changeset bank:ing-003-revoke-staging-grant
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM user_tab_privs WHERE grantee = 'UPG_OWNER' AND table_name = 'ACCOUNT_STAGING' AND privilege = 'SELECT'
REVOKE SELECT ON ACCOUNT_STAGING FROM UPG_OWNER;

-- Spring Batch keeps the restart position itself (BATCH_STEP_EXECUTION_CONTEXT, committed with each chunk).
--changeset bank:ing-004-drop-last-committed-row
ALTER TABLE FILE_INGESTION_LOG DROP COLUMN LAST_COMMITTED_ROW;
