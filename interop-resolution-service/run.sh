#!/usr/bin/env bash
# Starts the Interop Resolution Service. Needs Java 17 or newer on the PATH.
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
JAR=$(ls build/libs/interop-resolution-service-*.jar 2>/dev/null | grep -v '\-plain\.jar$' | head -n 1 || true)
if [ -z "$JAR" ]; then
  echo "No prebuilt jar in build/libs/, building (first build downloads Gradle and the dependencies)..."
  ./gradlew -q bootJar
  JAR=$(ls build/libs/interop-resolution-service-*.jar | grep -v '\-plain\.jar$' | head -n 1)
fi
echo "Starting $JAR (profile: ${SPRING_PROFILES_ACTIVE:-PQA, the default}; SPRING_PROFILES_ACTIVE=DEV for the in-process stub) -> http://localhost:${SERVER_PORT:-9090}/swagger-ui.html"
exec java -jar "$JAR" "$@"
