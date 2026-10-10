#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
HELPER="$PROJECT_DIR/hkex-cmdb-sync-server/bin/java17.sh"
TEMP_DIR=$(mktemp -d)
ORIGINAL_PATH=$PATH
trap 'rm -rf "$TEMP_DIR"' EXIT

make_java() {
    local home=$1
    local version=$2
    mkdir -p "$home/bin"
    printf '%s\n' '#!/bin/sh' "printf 'openjdk version \"$version\"\\n' >&2" > "$home/bin/java"
    chmod 755 "$home/bin/java"
}

make_java "$TEMP_DIR/jdk8" "1.8.0_452"
make_java "$TEMP_DIR/jdk17" "17.0.15"
mkdir -p "$TEMP_DIR/empty-path"

# shellcheck source=/dev/null
source "$HELPER"

JAVA_HOME="$TEMP_DIR/jdk8"
JAVA_17_HOME="$TEMP_DIR/jdk17"
PATH="$TEMP_DIR/empty-path"
export JAVA_HOME JAVA_17_HOME PATH
selected=$(resolve_java17)
[ "$selected" = "$TEMP_DIR/jdk17/bin/java" ]

JAVA_17_HOME="$TEMP_DIR/jdk8"
export JAVA_17_HOME
if resolve_java17 >/dev/null 2>&1; then
    echo "Expected Java 8 to be rejected" >&2
    exit 1
fi

PATH=$ORIGINAL_PATH
export PATH
printf 'Java 17 resolution: OK\n'