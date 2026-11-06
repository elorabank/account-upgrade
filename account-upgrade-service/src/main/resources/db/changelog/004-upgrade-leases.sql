--liquibase formatted sql

-- Several copies of the upgrade service can now work on the same run. A claimed request carries a lease: who has it
-- (CLAIMED_BY, the copy's instance id) and until when (CLAIMED_UNTIL, renewed while the copy works). A request whose
-- lease ran out belongs to a copy that died, and any copy may take it over.
--changeset bank:upg-004-leases
ALTER TABLE UPGRADE_REQUEST ADD (CLAIMED_BY VARCHAR2(100 CHAR), CLAIMED_UNTIL TIMESTAMP WITH TIME ZONE);
CREATE INDEX IX_UPGRADE_CLAIM ON UPGRADE_REQUEST (STATUS, CLAIMED_UNTIL);
