#!/usr/bin/env bash
#
# AiSteve — development launcher (Linux / macOS)
#
# Runs a development Minecraft client with the mod loaded.
# Equivalent to: ./gradlew runClient
#
# JDK 17 is required. This script does not bundle one — it just checks.

set -euo pipefail

cd "$(dirname "$0")/.."

echo "AiSteve — dev launcher"
echo "======================"
echo

# ---------------------------------------------------------------------------
# Check for JDK 17 (Forge 1.20.1 requires exactly 17; 21+ breaks the build).
# ---------------------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
    echo "!! java not found on PATH." >&2
    echo "   Install JDK 17 (e.g. Eclipse Temurin) and set JAVA_HOME." >&2
    exit 1
fi

JAVA_VERSION="$(java -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [ "${JAVA_VERSION}" != "17" ]; then
    echo "!! Detected Java ${JAVA_VERSION}, but this project needs Java 17." >&2
    echo "   Set JAVA_HOME to a JDK 17 installation and try again." >&2
    exit 1
fi
echo "OK  Java 17"

# ---------------------------------------------------------------------------
# Warn early about a missing API key: otherwise the client starts and the AI
# silently does nothing, which looks like a mod bug.
# ---------------------------------------------------------------------------
CONFIG="config/aisteve-common.toml"
EXAMPLE="config/aisteve-common.toml.example"

if [ ! -f "${CONFIG}" ]; then
    echo "!! ${CONFIG} not found."
    if [ -f "${EXAMPLE}" ]; then
        echo "   Copy the template and fill in your API key:"
        echo "     cp ${EXAMPLE} ${CONFIG}"
    fi
    echo "   (The file is also generated on first launch.)"
elif ! grep -qE 'apiKey[[:space:]]*=[[:space:]]*"[^"]+"' "${CONFIG}"; then
    echo "!! No API key configured in ${CONFIG}."
    echo "   The AI will not respond until you add one."
fi
echo

# ---------------------------------------------------------------------------
# Launch
# ---------------------------------------------------------------------------
echo "Starting Minecraft (first launch downloads assets, 1-2 minutes)..."
echo

./gradlew runClient --no-daemon

echo
echo "======================"
echo "Minecraft closed."
