--liquibase formatted sql

-- A letter keeps the values that fill its template (about 60 bytes) instead of the finished HTML (about 2 KB):
-- the email is built from TEMPLATE_ID and these values when it is sent. Letters written before this change keep
-- their HTML_BODY and are sent from it; new letters leave it empty. Each letter has one or the other.
--changeset bank:com-005-compact-letters
ALTER TABLE WELCOME_LETTER ADD (
    CUSTOMER_NAME VARCHAR2(200 CHAR),
    FROM_TYPE     VARCHAR2(30 CHAR),
    TO_TYPE       VARCHAR2(30 CHAR),
    BUSINESS_DATE DATE
);
ALTER TABLE WELCOME_LETTER MODIFY (HTML_BODY NULL);
ALTER TABLE WELCOME_LETTER ADD CONSTRAINT CK_LETTER_CONTENT CHECK (
    HTML_BODY IS NOT NULL OR (CUSTOMER_NAME IS NOT NULL AND TO_TYPE IS NOT NULL AND BUSINESS_DATE IS NOT NULL));

-- A letter points at the template version it was written from, so that version must never change: a resent or
-- retried email has to say what the first one said. To change a letter, insert a new VERSION_NO and make it ACTIVE.
-- Turning ACTIVE on or off is still allowed. (LOB columns cannot be named in UPDATE OF, hence the comparison.)
--changeset bank:com-005-immutable-templates splitStatements:false endDelimiter:/
CREATE OR REPLACE TRIGGER TRG_LETTER_TEMPLATE_IMMUTABLE
BEFORE UPDATE ON LETTER_TEMPLATE FOR EACH ROW
BEGIN
    IF :OLD.SUBJECT <> :NEW.SUBJECT
       OR :OLD.ACCOUNT_TYPE <> :NEW.ACCOUNT_TYPE
       OR :OLD.TEMPLATE_CODE <> :NEW.TEMPLATE_CODE
       OR :OLD.VERSION_NO <> :NEW.VERSION_NO
       OR DBMS_LOB.COMPARE(:OLD.BODY_HTML, :NEW.BODY_HTML) <> 0 THEN
        RAISE_APPLICATION_ERROR(-20002, 'A letter template cannot be changed: insert a new VERSION_NO instead');
    END IF;
END;
/
