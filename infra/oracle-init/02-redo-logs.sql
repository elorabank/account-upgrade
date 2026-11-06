-- Bigger redo logs for local bulk runs (perf/run.sh). Oracle Free starts with two 10 MB groups, which fill in seconds
-- under load and make every commit wait for a checkpoint ("log file switch (checkpoint incomplete)").
-- Three 512 MB groups instead. Runs on first start of a new volume; for an existing one run it once by hand:
--   docker compose exec -T oracle sqlplus -s / as sysdba < infra/oracle-init/02-redo-logs.sql
-- Safe to run again: it does nothing once the large groups exist.
SET SERVEROUTPUT ON
DECLARE
    big_groups NUMBER;
    small_left NUMBER;
BEGIN
    SELECT COUNT(*) INTO big_groups FROM v$log WHERE bytes >= 512 * 1024 * 1024;
    IF big_groups >= 3 THEN
        DBMS_OUTPUT.PUT_LINE('Redo logs already resized');
        RETURN;
    END IF;
    FOR g IN 3 .. 5 LOOP
        EXECUTE IMMEDIATE 'ALTER DATABASE ADD LOGFILE GROUP ' || g
            || ' (''/opt/oracle/oradata/FREE/redo0' || g || '.log'') SIZE 512M';
    END LOOP;
    -- Move writing onto the new groups and let the old ones be written out, so they can be dropped
    FOR attempt IN 1 .. 10 LOOP
        EXECUTE IMMEDIATE 'ALTER SYSTEM SWITCH LOGFILE';
        EXECUTE IMMEDIATE 'ALTER SYSTEM CHECKPOINT';
        SELECT COUNT(*) INTO small_left FROM v$log WHERE group# IN (1, 2) AND status IN ('CURRENT', 'ACTIVE');
        EXIT WHEN small_left = 0;
    END LOOP;
    EXECUTE IMMEDIATE 'ALTER DATABASE DROP LOGFILE GROUP 1';
    EXECUTE IMMEDIATE 'ALTER DATABASE DROP LOGFILE GROUP 2';
    DBMS_OUTPUT.PUT_LINE('Redo logs now 3 x 512 MB');
END;
/
