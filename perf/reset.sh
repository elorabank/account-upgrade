#!/usr/bin/env bash
# LOCAL ONLY. Deletes all account-upgrade data so a performance run starts clean:
#   every row in the four schemas (tables, sequences and the letter templates stay; the insert-only audit
#   table is emptied with its trigger briefly disabled), all Kafka topics and consumer positions (Kafka is
#   recreated with no data), the SFTP folders and the Mailpit inbox. Running services may stay up.
#   Usage: perf/reset.sh [--yes]
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [[ "${1:-}" != "--yes" ]]; then
  read -r -p "This deletes ALL local account-upgrade data (database, Kafka, SFTP, Mailpit). Continue? [y/N] " answer
  [[ "$answer" == [yY] ]] || { echo "Nothing changed."; exit 1; }
fi

echo "Database..."
sql "
whenever sqlerror exit failure rollback
delete from ING_OWNER.ACCOUNT_STAGING;
delete from ING_OWNER.INGESTION_ERROR;
delete from ING_OWNER.FILE_INGESTION_LOG;
delete from ING_OWNER.OUTBOX_EVENT;
delete from ING_OWNER.SHEDLOCK;
delete from ING_OWNER.BATCH_STEP_EXECUTION_CONTEXT;
delete from ING_OWNER.BATCH_STEP_EXECUTION;
delete from ING_OWNER.BATCH_JOB_EXECUTION_CONTEXT;
delete from ING_OWNER.BATCH_JOB_EXECUTION_PARAMS;
delete from ING_OWNER.BATCH_JOB_EXECUTION;
delete from ING_OWNER.BATCH_JOB_INSTANCE;
delete from UPG_OWNER.UPGRADE_REQUEST;
delete from UPG_OWNER.OUTBOX_EVENT;
delete from UPG_OWNER.PROCESSED_EVENT;
delete from COM_OWNER.WELCOME_LETTER;
delete from COM_OWNER.OUTBOX_EVENT;
delete from COM_OWNER.PROCESSED_EVENT;
delete from AUD_OWNER.PROCESSED_EVENT;
commit;
alter trigger AUD_OWNER.TRG_AUDIT_EVENT_IMMUTABLE disable;
delete from AUD_OWNER.AUDIT_EVENT;
commit;
alter trigger AUD_OWNER.TRG_AUDIT_EVENT_IMMUTABLE enable;
select '  rows left: ' || ((select count(*) from ING_OWNER.FILE_INGESTION_LOG) + (select count(*) from UPG_OWNER.UPGRADE_REQUEST)
  + (select count(*) from COM_OWNER.WELCOME_LETTER) + (select count(*) from AUD_OWNER.AUDIT_EVENT)) from dual;
"
echo "Kafka (recreated without data)..."
docker compose rm -sf kafka >/dev/null 2>&1
docker compose up -d kafka >/dev/null 2>&1
until docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; do sleep 2; done
echo "SFTP folders and Mailpit..."
docker compose exec -T sftp sh -c 'rm -f /home/bank/incoming/* /home/bank/processed/* /home/bank/error/*'
curl -s -X DELETE http://localhost:8025/api/v1/messages -o /dev/null
echo "Clean."
