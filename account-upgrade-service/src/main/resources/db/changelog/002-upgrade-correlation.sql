--liquibase formatted sql

--changeset bank:upg-002-correlation
ALTER TABLE UPGRADE_REQUEST ADD (CORRELATION_ID VARCHAR2(64 CHAR));
