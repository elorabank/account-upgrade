#!/usr/bin/env bash
# Makes a throw-away certificate authority and a broker certificate for the LOCAL secured Kafka.
# Output goes to infra/kafka-secure/certs (not committed). Run once: ./infra/kafka-secure/generate-certs.sh
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/Users/jovi/Library/Java/JavaVirtualMachines/openjdk-21/Contents/Home}"
KEYTOOL="${JAVA_HOME}/bin/keytool"
PASS="${KAFKA_TLS_PASSWORD:-kafka-local-tls}"
cd "$(dirname "$0")"
rm -rf certs && mkdir certs && cd certs

# certificate authority
"$KEYTOOL" -genkeypair -alias ca -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=account-upgrade-local-ca" \
  -ext bc:c -keystore ca.p12 -storetype PKCS12 -storepass "$PASS"
"$KEYTOOL" -exportcert -alias ca -keystore ca.p12 -storepass "$PASS" -rfc -file ca.crt

# broker key, signed by the CA; reachable as localhost (apps on your machine) and kafka-secure (inside compose)
SAN="dns:localhost,dns:kafka-secure,ip:127.0.0.1"
"$KEYTOOL" -genkeypair -alias broker -keyalg RSA -keysize 2048 -validity 825 -dname "CN=localhost" \
  -ext "SAN=$SAN" -keystore broker.p12 -storetype PKCS12 -storepass "$PASS"
"$KEYTOOL" -certreq -alias broker -keystore broker.p12 -storepass "$PASS" -file broker.csr
"$KEYTOOL" -gencert -alias ca -keystore ca.p12 -storepass "$PASS" -infile broker.csr -outfile broker.crt \
  -ext "SAN=$SAN" -validity 825 -rfc
"$KEYTOOL" -importcert -noprompt -alias ca -file ca.crt -keystore broker.p12 -storepass "$PASS"
"$KEYTOOL" -importcert -noprompt -alias broker -file broker.crt -keystore broker.p12 -storepass "$PASS"

# what clients trust
"$KEYTOOL" -importcert -noprompt -alias ca -file ca.crt -keystore truststore.p12 -storetype PKCS12 -storepass "$PASS"
rm -f broker.csr ca.p12
chmod -R a+rX .
echo "Certificates written to $(pwd)"
