#!/usr/bin/env bash
# Creates the topics and gives each service only what it needs. Safe to run again.
set -euo pipefail
BIN=/opt/kafka/bin
BOOT=kafka-secure:9097
CFG=/config/admin.properties

topic() { $BIN/kafka-topics.sh --bootstrap-server $BOOT --command-config $CFG --create --if-not-exists --topic "$1" --partitions 3 --replication-factor 1 >/dev/null; }
acl()   { $BIN/kafka-acls.sh --bootstrap-server $BOOT --command-config $CFG --add "$@" >/dev/null; }
# Logins are created when storage is formatted (start.sh); this adds any that are missing on an older volume
user()  { $BIN/kafka-configs.sh --bootstrap-server $BOOT --command-config $CFG --alter --entity-type users --entity-name "$1" \
            --add-config "SCRAM-SHA-512=[password=$1-kafka-local-pw]" >/dev/null; }
produce() { acl --allow-principal "User:$1" --producer --topic "$2"; }                    # write
consume() { acl --allow-principal "User:$1" --consumer --topic "$2" --group "$1"; }      # read, as the service's own consumer group
park()    { acl --allow-principal "User:$1" --producer --topic "$2.DLT"; }               # write to the dead-letter topic of a topic it reads

FILE_LOADED=account.file-loaded; UPGRADED=account.upgraded; FAILED=account.upgrade-failed; REJECTED=account.upgrade-rejected
SENT=notification.sent; NOTSENT=notification.failed; USER_ACTION=audit.user-action
ING=file-ingestion-service; UPG=account-upgrade-service; COM=communication-service; AUD=audit-service
ALL="$FILE_LOADED $UPGRADED $FAILED $REJECTED $SENT $NOTSENT $USER_ACTION"

for t in $ALL; do topic "$t"; topic "$t.DLT"; done
user $COM

# ingestion: publishes file-loaded
produce $ING $FILE_LOADED;  produce $ING $USER_ACTION
# upgrade: reads file-loaded, publishes upgraded / failed
consume $UPG $FILE_LOADED;  park $UPG $FILE_LOADED
produce $UPG $UPGRADED;     produce $UPG $FAILED;  produce $UPG $REJECTED; produce $UPG $USER_ACTION
# communication: reads upgraded, builds the letter, emails it, publishes sent / failed
consume $COM $UPGRADED;     park $COM $UPGRADED
produce $COM $SENT;         produce $COM $NOTSENT; produce $COM $USER_ACTION
# audit: reads everything, publishes nothing
for t in $ALL; do consume $AUD $t; park $AUD $t; done

echo "Topics and ACLs ready:"
$BIN/kafka-acls.sh --bootstrap-server $BOOT --command-config $CFG --list | grep -c "principal=User" | sed 's/^/  ACL entries: /'
