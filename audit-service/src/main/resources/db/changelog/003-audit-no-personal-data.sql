--liquibase formatted sql

-- The audit trail is kept forever and cannot be changed, so it must not hold customer names or emails.
-- It keeps who/what/when, the account number and a short summary; the details stay in the owning service.
--changeset bank:aud-003-no-personal-data
ALTER TABLE AUDIT_EVENT DROP COLUMN PAYLOAD;
ALTER TABLE AUDIT_EVENT ADD (SUMMARY VARCHAR2(1000 CHAR));
