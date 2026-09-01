#!/bin/sh

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-8.4-bin/1w5dpkrfk8irigvoxmyhowfim"

if [ ! -d "$GRADLE_DIST_DIR/gradle-8.4" ]; then
    mkdir -p "$GRADLE_DIST_DIR"
    cd "$GRADLE_DIST_DIR"
    if [ ! -f gradle-8.4-bin.zip ]; then
        curl -L -o gradle-8.4-bin.zip "https://services.gradle.org/distributions/gradle-8.4-bin.zip"
    fi
    unzip -q gradle-8.4-bin.zip
fi

exec "$GRADLE_DIST_DIR/gradle-8.4/bin/gradle" "$@"
