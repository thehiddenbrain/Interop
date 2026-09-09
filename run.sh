#!/usr/bin/env bash
# Starts the CMS-1500 claim bundle service. Needs Java 17 or newer on the PATH.
# Uses the prebuilt jar in target/ when present, otherwise builds it with the Maven wrapper.
set -e
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  echo "java not found on PATH. Install Java 17 or newer (https://adoptium.net) and retry." >&2
  exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print (v[1]=="1") ? v[2] : v[1]}')
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 17 ]; then
  echo "Java $JAVA_MAJOR found; Java 17 or newer is required." >&2
  exit 1
fi
JAR=$(ls target/cms1500-claim-service-*.jar 2>/dev/null | head -n 1 || true)
if [ -z "$JAR" ]; then
  echo "No prebuilt jar in target/, building (first build downloads dependencies)..."
  ./mvnw -q -DskipTests package
  JAR=$(ls target/cms1500-claim-service-*.jar | head -n 1)
fi
mkdir -p data/attachments data/bundles
echo "Starting $JAR (profile: ${SPRING_PROFILES_ACTIVE:-dev}, attachments: ${CMS1500_ATTACHMENTS_DIR:-./data/attachments}, bundles: ${CMS1500_OUTPUT_DIR:-./data/bundles})"
exec java -jar "$JAR" "$@"
