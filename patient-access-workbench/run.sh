#!/usr/bin/env bash
# Starts the Patient Access API workbench. Needs Java 17 or newer on the PATH.
# Uses the prebuilt jar in build/libs/ when present, otherwise builds it with the Gradle wrapper.
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
JAR=$(ls build/libs/patient-access-workbench-*.jar 2>/dev/null | grep -v '\-plain\.jar$' | head -n 1 || true)
if [ -z "$JAR" ]; then
  echo "No prebuilt jar in build/libs/, building (first build downloads Gradle and the dependencies)..."
  ./gradlew -q bootJar
  JAR=$(ls build/libs/patient-access-workbench-*.jar | grep -v '\-plain\.jar$' | head -n 1)
fi
mkdir -p "${PAW_DATA_DIR:-./data}"
echo "Starting $JAR (profile: ${SPRING_PROFILES_ACTIVE:-dev}, data: ${PAW_DATA_DIR:-./data}) -> http://localhost:${SERVER_PORT:-8090}/ui/"
exec java -jar "$JAR" "$@"
