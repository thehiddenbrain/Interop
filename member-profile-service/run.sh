#!/usr/bin/env bash
# Starts the Member Profile Service. Needs Java 17 or newer on the PATH and a PostgreSQL reachable
# with MEMBER_PROFILE_DB_URL / _USER / _PASSWORD (defaults: localhost:5432/member_profile).
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
JAR=$(ls build/libs/member-profile-service-*.jar 2>/dev/null | grep -v '\-plain\.jar$' | head -n 1 || true)
if [ -z "$JAR" ]; then
  echo "No prebuilt jar in build/libs/, building (first build downloads Gradle and the dependencies)..."
  ./gradlew -q bootJar
  JAR=$(ls build/libs/member-profile-service-*.jar | grep -v '\-plain\.jar$' | head -n 1)
fi
echo "Starting $JAR (profile: ${SPRING_PROFILES_ACTIVE:-dev}, db: ${MEMBER_PROFILE_DB_URL:-jdbc:postgresql://localhost:5432/member_profile}) -> http://localhost:${SERVER_PORT:-8081}/swagger-ui.html"
exec java -jar "$JAR" "$@"
