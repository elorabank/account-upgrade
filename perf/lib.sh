# Shared helpers for the performance scripts. Local docker-compose only.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Runs SQL as SYS in the local Oracle and prints the rows without headings
sql() {
  docker compose exec -T oracle sqlplus -s / as sysdba <<SQL | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e '/^$/d'
set heading off feedback off pagesize 0 linesize 400 trimspool on verify off tab off
alter session set container=FREEPDB1;
$1
SQL
}

# A client-credentials token from the local Keycloak
token() {  # client scope
  curl -s -X POST http://localhost:8180/realms/account-upgrade/protocol/openid-connect/token \
    -d grant_type=client_credentials -d client_id="$1" -d client_secret=local-secret -d scope="$2" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'
}

# Waits until a command prints something starting with "yes", showing its progress; gives up after $3 seconds
wait_for() {  # label command timeout-seconds
  local label=$1 check=$2 limit=$3 start=$SECONDS out
  while true; do
    out=$(eval "$check")
    if [[ "$out" == yes* ]]; then printf '\r  %-30s done after %ss%*s\n' "$label" $((SECONDS - start)) 40 ''; return 0; fi
    if (( SECONDS - start > limit )); then printf '\n  %s: gave up after %ss (%s)\n' "$label" "$limit" "$out"; return 1; fi
    printf '\r  %-30s %s (%ss)    ' "$label" "$out" $((SECONDS - start))
    sleep 3
  done
}
