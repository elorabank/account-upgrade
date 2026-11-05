#!/usr/bin/env bash
# Formats storage on first start (creating the SCRAM logins), then runs the broker.
set -euo pipefail
KAFKA=/opt/kafka/bin
DATA=/var/lib/kafka/data
CLUSTER_ID="MkU3OEVBNTcwNTJENDM2Qk"   # fixed so a restart keeps the same cluster

# One login per service (user name = application name), plus an admin. Passwords are local-only.
scram() { echo "SCRAM-SHA-512=[name=$1,password=$2]"; }

if [ ! -f "$DATA/meta.properties" ]; then
  $KAFKA/kafka-storage.sh format -t "$CLUSTER_ID" -c /config/server.properties \
    --add-scram "$(scram admin admin-kafka-local-pw)" \
    --add-scram "$(scram file-ingestion-service file-ingestion-service-kafka-local-pw)" \
    --add-scram "$(scram account-upgrade-service account-upgrade-service-kafka-local-pw)" \
    --add-scram "$(scram communication-service communication-service-kafka-local-pw)" \
    --add-scram "$(scram audit-service audit-service-kafka-local-pw)"
fi
exec $KAFKA/kafka-server-start.sh /config/server.properties
