#!/usr/bin/env bash
# One performance run, from file to audit trail, then the timeline report. LOCAL ONLY.
#   1. generates accounts_<date>.xlsx with N rows and puts it in the SFTP incoming folder
#   2. presses "run now" for the load (as the perf-runner client), waits for the file and its planning
#   3. presses "run now" for the upgrade, waits for it, then for the emails, the outbox and the audit trail
# Start the apps with the local profile first (IntelliJ: "Upgrade flow (all services)"). Each run uses its own business
# date (by default the first unused one from 2030-01-01), so runs can sit side by side; perf/reset.sh is optional.
#   Usage: perf/run.sh [rows=100000] [business date yyyyMMdd]
set -euo pipefail
source "$(dirname "$0")/lib.sh"

ROWS=${1:-100000}
DATE=${2:-$(sql "select to_char(nvl(max(business_date) + 1, date '2030-01-01'), 'YYYYMMDD') from ING_OWNER.FILE_INGESTION_LOG
  where business_date >= date '2030-01-01';" | tr -d ' ')}
DAY="${DATE:0:4}-${DATE:4:2}-${DATE:6:2}"
if [[ $(sql "select count(*) from ING_OWNER.FILE_INGESTION_LOG where business_date = date '$DAY';" | tr -d ' ') != 0 ]]; then
  echo "Business date $DAY already has a run. Pick another date, or leave it out to use the next free one."; exit 1
fi
FILE="accounts_${DATE}.xlsx"
LIMIT=${PERF_TIMEOUT:-21600}   # seconds per stage before giving up (6 hours)

for port in 8081 8082 8083 8085; do
  curl -sf "http://localhost:$port/actuator/health" >/dev/null || { echo "Nothing answers on port $port: start the services first."; exit 1; }
done

echo "Generating $FILE with $ROWS rows..."
python3 samples/make_sample_accounts.py "$DATE" "$ROWS" perf/.work | sed 's/^/  /'
docker compose cp "perf/.work/$FILE" "sftp:/home/bank/incoming/$FILE" >/dev/null 2>&1
docker compose exec -T sftp chown 1001:100 "/home/bank/incoming/$FILE"

TOKEN=$(token perf-runner upgrade.admin)
run_now() {  # port job
  curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:$1/api/jobs/$2/run"
}

echo "Load (run now: $(run_now 8081 ingestion))"
wait_for "file loaded" "sql \"select case status when 'COMPLETED' then 'yes' when 'FAILED' then 'FAILED: ' || error_message else status end
  from ING_OWNER.FILE_INGESTION_LOG where file_name = '$FILE';\"" "$LIMIT"
LOADED=$(sql "select loaded_rows from ING_OWNER.FILE_INGESTION_LOG where file_name = '$FILE';" | tr -d ' ')
wait_for "requests planned" "sql \"select case when count(*) >= $LOADED then 'yes' else count(*) || ' of $LOADED' end
  from UPG_OWNER.UPGRADE_REQUEST where business_date = date '$DAY';\"" "$LIMIT"

echo "Upgrade (run now: $(run_now 8082 upgrade))"
# Done when nothing is in progress and no request is still untried (one the run tried but could not finish keeps
# the run's id and waits for the next run). Every running copy joins, so this counts all copies' work.
wait_for "upgrade run finished" "sql \"select case when sum(case when status = 'IN_PROGRESS' or (status = 'PENDING' and run_id is null) then 1 else 0 end) = 0
  then 'yes' else sum(case when status = 'UPGRADED' then 1 else 0 end) || ' upgraded' end
  from UPG_OWNER.UPGRADE_REQUEST where business_date = date '$DAY';\"" "$LIMIT"
UPGRADED=$(sql "select count(*) from UPG_OWNER.UPGRADE_REQUEST where business_date = date '$DAY' and status = 'UPGRADED';" | tr -d ' ')
OF_DAY="upgrade_request_id in (select id from UPG_OWNER.UPGRADE_REQUEST where business_date = date '$DAY')"
wait_for "emails sent" "sql \"select case when count(*) >= $UPGRADED and sum(case when status = 'PENDING' then 1 else 0 end) = 0 then 'yes'
  else nvl(sum(case when status = 'SENT' then 1 else 0 end), 0) || ' sent of $UPGRADED' end from COM_OWNER.WELCOME_LETTER where $OF_DAY;\"" "$LIMIT"
wait_for "outboxes empty" "sql \"select case when n = 0 then 'yes' else n || ' events waiting' end from (select
  (select count(*) from ING_OWNER.OUTBOX_EVENT where status = 'NEW') + (select count(*) from UPG_OWNER.OUTBOX_EVENT where status = 'NEW')
  + (select count(*) from COM_OWNER.OUTBOX_EVENT where status = 'NEW') n from dual);\"" "$LIMIT"
FROM="(select min(started_at) from ING_OWNER.FILE_INGESTION_LOG where business_date = date '$DAY')"
# Every business event is audited; the "run now" signal to the upgrade copies (upgrade.run-requested) is not
EXPECTED=$(sql "select (select count(*) from ING_OWNER.OUTBOX_EVENT where created_at >= $FROM)
  + (select count(*) from UPG_OWNER.OUTBOX_EVENT where created_at >= $FROM and topic <> 'upgrade.run-requested')
  + (select count(*) from COM_OWNER.OUTBOX_EVENT where created_at >= $FROM) from dual;" | tr -d ' ')
wait_for "audit trail complete" "sql \"select case when count(*) >= $EXPECTED then 'yes' else count(*) || ' of $EXPECTED' end
  from AUD_OWNER.AUDIT_EVENT where recorded_at >= $FROM;\"" "$LIMIT"

mkdir -p perf/reports
REPORT="perf/reports/$(date +%Y%m%d-%H%M%S)-${ROWS}.txt"
{ echo "Run: $ROWS rows, business date $DAY, $(date)"; perf/report.sh "$DATE"; } | tee "$REPORT"
echo "Saved to $REPORT"
