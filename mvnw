#!/bin/sh
# ---------------------------------------------------------------------------
# NexoSync build bootstrap.
#
# Downloads a pinned Apache Maven distribution into the user's home directory
# the first time it runs, then delegates every argument to it. This keeps the
# build reproducible on machines that do not have Maven installed.
#
#   ./mvnw package
# ---------------------------------------------------------------------------
set -e

MAVEN_VERSION="3.9.11"
DIST_BASE="${NEXOSYNC_MAVEN_MIRROR:-https://archive.apache.org/dist/maven/maven-3}"
DIST_URL="$DIST_BASE/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip"

WRAPPER_HOME="${MAVEN_WRAPPER_HOME:-$HOME/.m2/wrapper/dists}"
MAVEN_DIR="$WRAPPER_HOME/apache-maven-$MAVEN_VERSION"

if [ ! -f "$MAVEN_DIR/bin/mvn" ]; then
    echo "[NexoSync] Apache Maven $MAVEN_VERSION not found locally, downloading..."
    mkdir -p "$WRAPPER_HOME"
    ARCHIVE="$WRAPPER_HOME/apache-maven-$MAVEN_VERSION-bin.zip"

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$ARCHIVE" "$DIST_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$ARCHIVE" "$DIST_URL"
    else
        echo "[NexoSync] Neither curl nor wget is available." >&2
        exit 1
    fi

    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$ARCHIVE" -d "$WRAPPER_HOME"
    elif command -v jar >/dev/null 2>&1; then
        ( cd "$WRAPPER_HOME" && jar xf "$ARCHIVE" )
    else
        echo "[NexoSync] Unable to extract the Maven archive (no unzip, no jar)." >&2
        exit 1
    fi

    rm -f "$ARCHIVE"
    chmod +x "$MAVEN_DIR/bin/mvn" 2>/dev/null || true
    echo "[NexoSync] Apache Maven $MAVEN_VERSION installed at $MAVEN_DIR"
fi

exec sh "$MAVEN_DIR/bin/mvn" "$@"
