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
            echo "Java: the first Java 21 or newer of PAVE_JAVA (a java executable), JAVA_HOME and the PATH."
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

# The first Java 21 or newer wins, looked for in this order: PAVE_JAVA (a java executable),
# JAVA_HOME, then the java on the PATH, the same order as run.cmd. An older Java is passed over
# with a line that says so instead of ending the search, because a JAVA_HOME left at Java 8 must
# not hide a Java 21 on the PATH.
JAVA=""
source_words=""
version=""
major=""

# Reads the version of the java given as $1 into version and major. "java -version" prints for
# example: openjdk version "21.0.5" 2024-10-15. Java 8 and older print "1.8.0_402", where the major
# version is the second number.
read_version() {
    local version_line
    version_line="$("$1" -version 2>&1 | grep -i 'version' | head -n 1 || true)"
    version="$(printf '%s\n' "$version_line" | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
    major="${version%%.*}"
    if [ "$major" = "1" ]; then
        major="$(printf '%s\n' "$version" | cut -d. -f2)"
    fi
}

# Tries one java: $1 is its path, $2 says where it was found.
try_java() {
    if [ ! -x "$1" ] || [ -d "$1" ]; then
        echo "Not using $1, $2: there is no such program."
        return 0
    fi
    read_version "$1"
    case "$major" in
        ''|*[!0-9]*)
            echo "Not using $1, $2: its Java version could not be read."
            return 0
            ;;
    esac
    if [ "$major" -lt 21 ]; then
        echo "Not using $1, $2: it is Java ${version}, and PAVE needs 21 or newer."
        return 0
    fi
    JAVA="$1"
    source_words="$2"
}

if [ -n "${PAVE_JAVA:-}" ]; then
    try_java "$PAVE_JAVA" "named by PAVE_JAVA"
fi
if [ -z "$JAVA" ] && [ -n "${JAVA_HOME:-}" ]; then
    try_java "${JAVA_HOME}/bin/java" "named by JAVA_HOME"
fi
if [ -z "$JAVA" ] && command -v java >/dev/null 2>&1; then
    try_java "$(command -v java)" "the java on the PATH"
fi
if [ -z "$JAVA" ]; then
    echo "run.sh: no Java 21 or newer was found, and PAVE needs one to build and to run." >&2
    echo "It looked at PAVE_JAVA, JAVA_HOME and the java on the PATH." >&2
    echo "Install a JDK 21 and set JAVA_HOME to it, or point PAVE_JAVA at a java 21 or newer, for example:" >&2
    echo "PAVE_JAVA=/path/to/jdk-21/bin/java ./run.sh" >&2
    exit 1
fi
echo "Using Java ${version} at ${JAVA}, ${source_words}."

# Gradle builds with the same Java, so a JAVA_HOME left at an older Java cannot stop the build.
# The java on the PATH can be a link with no Java home above it, so Gradle is then left to find it
# on the PATH.
if [ "$source_words" = "the java on the PATH" ] || [ "${JAVA#*/}" = "$JAVA" ]; then
    unset JAVA_HOME
else
    JAVA_HOME="$(cd "${JAVA%/*}/.." && pwd)"
    export JAVA_HOME
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
