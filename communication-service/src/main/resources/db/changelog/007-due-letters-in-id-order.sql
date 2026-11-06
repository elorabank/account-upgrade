--liquibase formatted sql

-- The sender asks for the oldest due letters: PENDING, in id order, the first 100. An index on (STATUS, ID) gives
-- them in that order and stops after 100 entries, however many letters the table holds. The old index on
-- (STATUS, NEXT_ATTEMPT_AT) could only find every pending letter and leave the sorting to Oracle.
-- The query names this index (a hint), so it does not depend on the table's statistics: right after the table was
-- partitioned they claimed a third of all letters were pending, and Oracle read the whole table every 5 seconds
-- (23 s a round instead of milliseconds). ONLINE: letters keep being written and sent while it is built.
--changeset bank:com-007-letter-status-id-index
CREATE INDEX IX_LETTER_STATUS_ID ON WELCOME_LETTER (STATUS, ID) ONLINE;

--changeset bank:com-007-drop-letter-due-index
DROP INDEX IX_LETTER_DUE;
