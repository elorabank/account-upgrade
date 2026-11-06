#!/usr/bin/env bash
# Creates the local "account-upgrade" realm: groups, test users, and the OAuth2 clients the apps use.
# Runs once per Keycloak start (Keycloak keeps no data volume locally); does nothing if the realm exists.
# Everything here is local-only: fixed secrets and passwords for a developer machine.
set -euo pipefail
KC=/opt/keycloak/bin/kcadm.sh
REALM=account-upgrade
R="-r $REALM"

until $KC config credentials --server http://keycloak:8080 --realm master --user admin --password admin >/dev/null 2>&1; do
  echo "waiting for Keycloak..."; sleep 3
done
if $KC get "realms/$REALM" >/dev/null 2>&1; then
  echo "Realm $REALM already set up"; exit 0
fi

$KC create realms -s realm=$REALM -s enabled=true -s sslRequired=none -s accessTokenLifespan=900 -s displayName="Account upgrade (local)"

# --- what every token carries: our API audience and the person's groups -----------------------------------
API_SCOPE=$($KC create client-scopes $R -i -s name=account-upgrade-api -s protocol=openid-connect \
  -s 'attributes."include.in.token.scope"=false')
$KC create "client-scopes/$API_SCOPE/protocol-mappers/models" $R -s name=audience -s protocol=openid-connect \
  -s protocolMapper=oidc-audience-mapper -s 'config."included.custom.audience"=api://account-upgrade' \
  -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false' -s 'config."introspection.token.claim"=true'
$KC create "client-scopes/$API_SCOPE/protocol-mappers/models" $R -s name=groups -s protocol=openid-connect \
  -s protocolMapper=oidc-group-membership-mapper -s 'config."claim.name"=groups' -s 'config."full.path"=false' \
  -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=true' -s 'config."userinfo.token.claim"=true' \
  -s 'config."introspection.token.claim"=true'
$KC update "realms/$REALM/default-default-client-scopes/$API_SCOPE" $R -n

# --- API scopes a service may ask for (they appear in the token's "scope" claim) ----------------------------
declare -A SCOPE_ID
for s in upgrade.read upgrade.admin audit.read notification.resend staging.read metrics.read; do
  SCOPE_ID[$s]=$($KC create client-scopes $R -i -s name=$s -s protocol=openid-connect \
    -s 'attributes."include.in.token.scope"=true' -s 'attributes."display.on.consent.screen"=false')
done

# --- groups and people --------------------------------------------------------------------------------------
declare -A GROUP_ID
for g in AU_OPS_ADMIN AU_OPS_VIEWER AU_SUPPORT AU_AUDITOR; do
  GROUP_ID[$g]=$($KC create groups $R -i -s name=$g)
done
user() {  # user name, first, last, email, group
  local id
  id=$($KC create users $R -i -s username="$1" -s enabled=true -s firstName="$2" -s lastName="$3" -s email="$4" -s emailVerified=true)
  $KC set-password $R --username "$1" --new-password local-pw
  $KC update "users/$id/groups/${GROUP_ID[$5]}" $R -s realm=$REALM -s userId="$id" -s groupId="${GROUP_ID[$5]}" -n
}
user olivia.ops  Olivia Ops     ops.admin@bank.example AU_OPS_ADMIN
user victor.view Victor Viewer  viewer@bank.example    AU_OPS_VIEWER
user sam.support Sam    Support support@bank.example   AU_SUPPORT
user ada.audit   Ada    Auditor auditor@bank.example   AU_AUDITOR

# --- clients ------------------------------------------------------------------------------------------------
# The gateway signs people in (authorization code + PKCE) and logs them out of Keycloak too
$KC create clients $R -s clientId=api-gateway -s name="API gateway / ops console" -s secret=local-secret \
  -s publicClient=false -s standardFlowEnabled=true -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=false \
  -s 'redirectUris=["http://localhost:8080/login/oauth2/code/login"]' -s 'webOrigins=["http://localhost:8080"]' \
  -s 'attributes."pkce.code.challenge.method"=S256' -s 'attributes."post.logout.redirect.uris"=http://localhost:8080/'

service_client() {  # client id, allowed scopes...
  local id client=$1; shift
  id=$($KC create clients $R -i -s clientId="$client" -s secret=local-secret -s publicClient=false \
    -s standardFlowEnabled=false -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true)
  for s in "$@"; do $KC update "clients/$id/optional-client-scopes/${SCOPE_ID[$s]}" $R -n; done
}
service_client account-upgrade-service staging.read   # reads a loaded file's accounts from ingestion
service_client local-auditor audit.read               # for trying the audit API with curl
service_client perf-runner upgrade.admin              # perf/run.sh presses "run now" for the load and the upgrade
service_client prometheus metrics.read                # the local Prometheus reads /actuator/prometheus

echo "Realm $REALM ready: users olivia.ops, victor.view, sam.support, ada.audit (password local-pw)"
