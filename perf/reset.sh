#!/usr/bin/env bash
# LOCAL ONLY. Deletes all account-upgrade data so a run starts clean:
#   - every row in the four schemas; the tables, their first partition, the sequences, the letter templates and
#     Liquibase's own records stay, so the services start as if freshly migrated;
#   - the partitions the data made (months, files), so each table is back to its first one;
#   - all Kafka topics and consumer positions (Kafka is recreated with no data);
#   - Redis (gateway sessions, so everyone signs in again, and the core-call rate limit), when it is running;
#   - the SFTP folders, the Mailpit inbox and the audit archive files in the default folders.
# Running services may stay up. Keycloak, Grafana, Prometheus and perf/reports are not touched.
#   Usage: perf/reset.sh [--yes]
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [[ "${1:-}" != "--yes" ]]; then
  read -r -p "This deletes ALL local account-upgrade data (database, Kafka, Redis, SFTP, Mailpit, audit archives). Continue? [y/N] " answer
  [[ "$answer" == [yY] ]] || { echo "Nothing changed."; exit 1; }
fi

echo "Database..."
# TRUNCATE empties a table at once, without undo, and the audit table's insert-only trigger does not fire for it.
# Tables other tables point at (the file log, Spring Batch's) are emptied with DELETE, children first.
sql "
whenever sqlerror exit failure rollback
truncate table ING_OWNER.ACCOUNT_STAGING;
truncate table ING_OWNER.INGESTION_ERROR;
truncate table ING_OWNER.OUTBOX_EVENT;
delete from ING_OWNER.FILE_INGESTION_LOG;
delete from ING_OWNER.SHEDLOCK;
delete from ING_OWNER.BATCH_STEP_EXECUTION_CONTEXT;
delete from ING_OWNER.BATCH_STEP_EXECUTION;
delete from ING_OWNER.BATCH_JOB_EXECUTION_CONTEXT;
delete from ING_OWNER.BATCH_JOB_EXECUTION_PARAMS;
delete from ING_OWNER.BATCH_JOB_EXECUTION;
delete from ING_OWNER.BATCH_JOB_INSTANCE;
commit;
truncate table UPG_OWNER.UPGRADE_REQUEST;
truncate table UPG_OWNER.OUTBOX_EVENT;
truncate table UPG_OWNER.PROCESSED_EVENT;
truncate table COM_OWNER.WELCOME_LETTER;
truncate table COM_OWNER.OUTBOX_EVENT;
truncate table COM_OWNER.PROCESSED_EVENT;
truncate table AUD_OWNER.AUDIT_EVENT;
truncate table AUD_OWNER.AUDIT_ARCHIVE;
truncate table AUD_OWNER.PROCESSED_EVENT;
begin
  -- Every partition but the first (made with the table; a month-partitioned table cannot lose it)
  for p in (select table_owner, table_name, partition_name from dba_tab_partitions
            where table_owner in ('ING_OWNER', 'UPG_OWNER', 'COM_OWNER', 'AUD_OWNER') and partition_position > 1) loop
    execute immediate 'alter table ' || p.table_owner || '.' || p.table_name
      || ' drop partition \"' || p.partition_name || '\" update global indexes';
  end loop;
end;
/
select '  rows left: ' || ((select count(*) from ING_OWNER.FILE_INGESTION_LOG) + (select count(*) from ING_OWNER.ACCOUNT_STAGING)
  + (select count(*) from UPG_OWNER.UPGRADE_REQUEST) + (select count(*) from COM_OWNER.WELCOME_LETTER)
  + (select count(*) from AUD_OWNER.AUDIT_EVENT) + (select count(*) from AUD_OWNER.AUDIT_ARCHIVE))
  || ', partitions left: ' || (select count(*) from dba_tab_partitions
     where table_owner in ('ING_OWNER', 'UPG_OWNER', 'COM_OWNER', 'AUD_OWNER') and partition_position > 1)
  || ', letter templates kept: ' || (select count(*) from COM_OWNER.LETTER_TEMPLATE) from dual;
"
echo "Kafka (recreated without data)..."
docker compose rm -sf kafka >/dev/null 2>&1
docker compose up -d kafka >/dev/null 2>&1
until docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; do sleep 2; done
if docker compose ps --status running --services | grep -qx redis; then
  echo "Redis..."
  docker compose exec -T redis redis-cli -a local-redis-pw --no-auth-warning FLUSHALL >/dev/null
fi
echo "SFTP folders, Mailpit and audit archive files..."
docker compose exec -T sftp sh -c 'rm -f /home/bank/incoming/* /home/bank/processed/* /home/bank/error/*'
curl -s -X DELETE http://localhost:8025/api/v1/messages -o /dev/null
# The default archive folder (bank.audit.archive.dir) is relative to where the audit service runs; elsewhere
# (AUDIT_ARCHIVE_DIR) is left alone
rm -f audit-archive/audit-events-*.jsonl.gz* audit-service/audit-archive/audit-events-*.jsonl.gz*
echo "Clean."
