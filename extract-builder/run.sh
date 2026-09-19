#!/usr/bin/env bash
# Vendor Extract Builder: build (first time only) and start on http://localhost:8090
# Needs Java 17 or newer. Gradle is downloaded by the wrapper; nothing else to install.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found on the PATH. Install a JDK 17 or newer (https://adoptium.net) and run this again." >&2
  exit 1
fi
echo "Using $(java -version 2>&1 | head -n 1)"
echo
echo "Starting the Vendor Extract Builder on http://localhost:8090 ..."
echo "The first start downloads Gradle and the dependencies, which takes a few minutes."
echo "Press Ctrl+C to stop the application."
echo

# Open the browser once the app answers, without blocking the build output.
(
  for _ in $(seq 1 120); do
    sleep 2
    if curl -fs http://localhost:8090/api/v1/health >/dev/null 2>&1; then
      if command -v open >/dev/null 2>&1; then open http://localhost:8090/ui/;
      elif command -v xdg-open >/dev/null 2>&1; then xdg-open http://localhost:8090/ui/ >/dev/null 2>&1; fi
      break
    fi
  done
) &

chmod +x gradlew 2>/dev/null || true
./gradlew bootRun --console=plain
