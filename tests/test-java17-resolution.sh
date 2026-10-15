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

mkdir -p "$TEMP_DIR/service/bin" "$TEMP_DIR/service/conf" "$TEMP_DIR/caller"
cp "$PROJECT_DIR/hkex-cmdb-sync-server/bin/publishCmdbConfig" "$TEMP_DIR/service/bin/"
cp "$HELPER" "$TEMP_DIR/service/bin/"
printf '%s\n' 'CW_NACOS_SERVER=dummy-nacos' 'CW_NACOS_USERNAME=dummy-user' \
    'CW_NACOS_PASSWORD=dummy-encrypted-password' 'CW_NACOS_NAMESPACE=dummy-namespace' \
    > "$TEMP_DIR/service/conf/nacos.params"
cat > "$TEMP_DIR/jdk17/bin/java" <<'JAVA'
#!/usr/bin/env bash
if [ "$1" = "-version" ]; then
    printf 'openjdk version "17.0.15"\n' >&2
    exit 0
fi
printf '%s\n' "$@" > "$ARGUMENT_CAPTURE"
JAVA
chmod 755 "$TEMP_DIR/jdk17/bin/java"
JAVA_17_HOME="$TEMP_DIR/jdk17"
ARGUMENT_CAPTURE="$TEMP_DIR/arguments"
export JAVA_17_HOME ARGUMENT_CAPTURE
(
    cd "$TEMP_DIR/caller"
    bash "$TEMP_DIR/service/bin/publishCmdbConfig" --encrypt-only 'plain config.json' 'cipher config.json'
)
grep -Fx -- '--encrypt-only' "$ARGUMENT_CAPTURE"
grep -Fx -- "$TEMP_DIR/caller/plain config.json" "$ARGUMENT_CAPTURE"
grep -Fx -- "$TEMP_DIR/caller/cipher config.json" "$ARGUMENT_CAPTURE"
printf 'Java 17 resolution and encryption arguments: OK\n'