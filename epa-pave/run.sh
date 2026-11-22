#!/usr/bin/env bash
# Starts PAVE on the mock profile, or on the live profile with "./run.sh live".
# Anything after the profile is passed to the application, for example:
#   ./run.sh live --server.port=8091
#
# The script moves to its own directory first, so it can be started from anywhere, and the
# runtime folders config-store/ and run-history/ always land beside it, never in whichever
# directory the terminal happened to be in.
set -euo pipefail

script_dir="${0%/*}"
if [ "$script_dir" = "$0" ]; then
    script_dir="."
fi
cd "$script_dir"

NAME="PAVE"
VERSION="1.0.0"
JAR="build/libs/epa-pave-${VERSION}.jar"
PORT="8057"

profile="mock"
if [ "$#" -gt 0 ]; then
    case "$1" in
        mock|live)
            profile="$1"
            shift
            ;;
        -h|--help)
            echo "Usage: ./run.sh [mock|live] [application arguments]"
            echo "  ./run.sh                          mock profile on port ${PORT}"
            echo "  ./run.sh live                     live profile on port ${PORT}"
            echo "  ./run.sh mock --server.port=8091  mock profile on another port"
            exit 0
            ;;
        --*)
            ;;
        *)
            echo "run.sh: '$1' is not a profile. Use mock or live, for example: ./run.sh live" >&2
            exit 2
            ;;
    esac
fi

# The port shown below follows a --server.port argument when one is given.
for argument in "$@"; do
    case "$argument" in
        --server.port=*) PORT="${argument#--server.port=}" ;;
    esac
done

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="$(command -v java)"
else
    echo "run.sh: no Java was found. PAVE needs a JDK 21." >&2
    echo "Install a JDK 21, then either put its bin folder on the PATH or set JAVA_HOME to it." >&2
    exit 1
fi

# "java -version" prints for example: openjdk version "21.0.5" 2024-10-15. Java 8 and older
# print "1.8.0_402", where the major version is the second number.
version_line="$("$JAVA" -version 2>&1 | grep -i 'version' | head -n 1 || true)"
version="$(printf '%s\n' "$version_line" | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
major="${version%%.*}"
if [ "$major" = "1" ]; then
    major="$(printf '%s\n' "$version" | cut -d. -f2)"
fi
case "$major" in
    ''|*[!0-9]*)
        echo "run.sh: could not read the Java version from: ${version_line:-(no output)}" >&2
        echo "PAVE needs a JDK 21. Check that ${JAVA} is a Java installation." >&2
        exit 1
        ;;
esac
if [ "$major" -lt 21 ]; then
    echo "run.sh: ${JAVA} is Java ${version}. PAVE needs a JDK 21." >&2
    echo "Install a JDK 21 and set JAVA_HOME to it, or put its bin folder first on the PATH." >&2
    exit 1
fi

# The jar is built before every start, not only when it is missing. Every build calls itself
# 1.0.0, so after a pull or an edit the jar of an older build would start as if it were the new
# one, and that old jar is what answered an upload with an internal error after a pull. Gradle's
# up-to-date check makes this quick when nothing changed.
echo "Building ${JAR} with the Gradle wrapper. This is quick when nothing changed; tests are skipped"
echo "here, run ./gradlew clean build to run them."
if [ "$major" != "21" ]; then
    echo "Note: the build compiles with a JDK 21 toolchain when Gradle finds one. ${JAVA} is Java ${version};" >&2
    echo "if Gradle finds no JDK 21, the build compiles for Java 21 with this Java when it is a JDK, and says so." >&2
fi
if ! ./gradlew --console=plain bootJar; then
    echo "run.sh: the build failed, so ${NAME} was not started: the jar in build/libs could be older than the source." >&2
    echo "Gradle's lines above say why." >&2
    exit 1
fi

# The time the jar was written tells a fresh build from an old one, which the version cannot.
built="$(date -r "$JAR" '+%Y-%m-%d %H:%M:%S %Z' 2>/dev/null || true)"
echo "Starting ${NAME} ${VERSION} from ${JAR}, built ${built:-at a time this system does not report}."
echo "It answers on http://localhost:${PORT}/ (${profile} profile). Stop it with Ctrl+C."
exec "$JAVA" -jar "$JAR" "--spring.profiles.active=${profile}" "$@"
