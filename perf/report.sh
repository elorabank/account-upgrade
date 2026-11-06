#!/usr/bin/env bash
# Timeline of one performance run, read from the timestamps each stage writes. Run after perf/run.sh.
# A run is identified by its business date; rows without a date (events, audit) count from that run's load onwards.
#   Usage: perf/report.sh [business date yyyyMMdd]   (default: the latest run)
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [[ -n "${1:-}" ]]; then
  DAY="${1:0:4}-${1:4:2}-${1:6:2}"
else
  DAY=$(sql "select to_char(max(business_date), 'YYYY-MM-DD') from ING_OWNER.FILE_INGESTION_LOG where business_date >= date '2030-01-01';" | tr -d ' ')
fi
FROM="(select min(started_at) from ING_OWNER.FILE_INGESTION_LOG where business_date = date '$DAY')"
OF_DAY="upgrade_request_id in (select id from UPG_OWNER.UPGRADE_REQUEST where business_date = date '$DAY')"

# seconds between two timestamps, at millisecond precision
secs() {
  echo "round(extract(day from ($2)-($1))*86400 + extract(hour from ($2)-($1))*3600 + extract(minute from ($2)-($1))*60 + extract(second from ($2)-($1)), 1)"
}

stage() {  # name, table, start column, end column, condition[, row count expression]
  local d n=${6:-"count(*)"}; d=$(secs "min($3)" "max($4)")
  echo "select rpad('$1', 30)
      || lpad(nvl(to_char(cast(min($3) at time zone sessiontimezone as timestamp), 'HH24:MI:SS'), '-'), 10)
      || lpad(nvl(to_char(cast(max($4) at time zone sessiontimezone as timestamp), 'HH24:MI:SS'), '-'), 10)
      || lpad(nvl(to_char($d, 'FM999990.0'), '-'), 10)
      || lpad(to_char($n, 'FM999,999,990'), 12)
      || lpad(case when $d > 0 then to_char($n / $d, 'FM999,990') else '-' end, 10)
    from $2 where $5;"
}

echo "Business date $DAY"
printf '%-30s%10s%10s%10s%12s%10s\n' "Stage" "start UTC" "end UTC" "seconds" "rows" "rows/s"
printf '%.0s-' {1..82}; echo
sql "
$(stage 'Load file (ingestion)'     ING_OWNER.FILE_INGESTION_LOG started_at completed_at "business_date = date '$DAY'" "sum(total_rows)")
$(stage 'Plan requests (upgrade)'   UPG_OWNER.UPGRADE_REQUEST created_at created_at "business_date = date '$DAY'")
$(stage 'Upgrade run (core calls)'  UPG_OWNER.UPGRADE_REQUEST updated_at updated_at "business_date = date '$DAY' and status in ('UPGRADED','FAILED')")
$(stage 'Publish account.upgraded'  UPG_OWNER.OUTBOX_EVENT created_at published_at "topic = 'account.upgraded' and created_at >= $FROM")
$(stage 'Create letters (comm.)'    COM_OWNER.WELCOME_LETTER generated_at generated_at "$OF_DAY")
$(stage 'Send emails (comm.)'       COM_OWNER.WELCOME_LETTER sent_at sent_at "status = 'SENT' and $OF_DAY")
$(stage 'Publish notification.sent' COM_OWNER.OUTBOX_EVENT created_at published_at "topic = 'notification.sent' and created_at >= $FROM")
$(stage 'Record audit trail'        AUD_OWNER.AUDIT_EVENT recorded_at recorded_at "recorded_at >= $FROM")
"
printf '%.0s-' {1..82}; echo
sql "
select rpad('End to end (load start to last audit row)', 50)
  || lpad(nvl(to_char($(secs "$FROM" "(select max(recorded_at) from AUD_OWNER.AUDIT_EVENT where recorded_at >= $FROM)"), 'FM999990.0'), '-'), 10) || ' s' from dual;
select 'Upgrades: ' || listagg(status || ' ' || n, ', ') within group (order by status) from (select status, count(*) n from UPG_OWNER.UPGRADE_REQUEST where business_date = date '$DAY' group by status);
select 'Letters:  ' || listagg(status || ' ' || n, ', ') within group (order by status) from (select status, count(*) n from COM_OWNER.WELCOME_LETTER where $OF_DAY group by status);
"
