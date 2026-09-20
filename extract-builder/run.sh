#!/usr/bin/env bash
# Vendor Extract Builder: start on http://localhost:8090
# Needs Java 17 or newer (https://adoptium.net). Nothing else to install.
# If extract-builder.jar is next to this file it is run directly; otherwise the app is built with the
# Gradle wrapper (the first build downloads Gradle and the dependencies).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found on the PATH. Install a JDK 17 or newer (https://adoptium.net) and run this again." >&2
  exit 1
fi
echo "Using $(java -version 2>&1 | head -n 1)"
echo
echo "Starting the Vendor Extract Builder on http://localhost:8090 ..."
echo "The browser opens by itself once the app is up. Press Ctrl+C to stop it."
echo

# Open the browser once the app answers, without blocking the output.
(
  for _ in $(seq 1 150); do
    sleep 2
    if curl -fs http://localhost:8090/api/v1/health >/dev/null 2>&1; then
      if command -v open >/dev/null 2>&1; then open http://localhost:8090/ui/;
      elif command -v xdg-open >/dev/null 2>&1; then xdg-open http://localhost:8090/ui/ >/dev/null 2>&1; fi
      break
    fi
  done
) &

if [ -f extract-builder.jar ]; then
  exec java -jar extract-builder.jar "$@"
else
  echo "No prebuilt jar found, building with Gradle. The first build takes a few minutes."
  chmod +x gradlew 2>/dev/null || true
  exec ./gradlew bootRun --console=plain
fi
