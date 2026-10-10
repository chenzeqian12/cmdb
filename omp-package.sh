#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "$0")" && pwd)
SERVER_DIR="$PROJECT_DIR/hkex-cmdb-sync-server"
VERSION=${1:-}
PRODUCT_NAME=${OMP_PRODUCT_NAME:-hkexCmdbSync}
PRODUCT_VERSION=${OMP_PRODUCT_VERSION:-}

if [ -z "$VERSION" ]; then
    VERSION=$(awk '
        /<parent>/ { in_parent=1; next }
        /<\/parent>/ { in_parent=0; next }
        in_parent == 0 && /<version>[^<]+<\/version>/ {
            match($0, /<version>[^<]+<\/version>/)
            print substr($0, RSTART + 9, RLENGTH - 19)
            exit
        }
    ' "$PROJECT_DIR/pom.xml")
fi
[ -n "$PRODUCT_VERSION" ] || PRODUCT_VERSION=$VERSION

case "$PRODUCT_NAME:$PRODUCT_VERSION:$VERSION" in
    *[!A-Za-z0-9._:-]*)
        echo "Version and product values may only contain letters, digits, dot, dash and underscore" >&2
        exit 1
        ;;
esac

if ! command -v gtar >/dev/null 2>&1 || ! gtar --version | grep -q 'GNU tar'; then
    echo "OMP service packages must be built with GNU tar (gtar)" >&2
    exit 1
fi

if [ "${OMP_SKIP_BUILD:-0}" != "1" ]; then
    if [ -x "$PROJECT_DIR/mvnw" ]; then
        "$PROJECT_DIR/mvnw" -B -DskipTests package
    else
        mvn -B -DskipTests package
    fi
fi

SOURCE_TAR=$(find "$SERVER_DIR/target" -maxdepth 1 -type f \
    -name "hkexCmdbSync-$VERSION-*.tar.gz" -print | sort | tail -1)
if [ -z "$SOURCE_TAR" ] || [ ! -f "$SOURCE_TAR" ]; then
    echo "CMDB assembly package not found; run mvn package first" >&2
    exit 1
fi

DIST_DIR="$PROJECT_DIR/dist/omp"
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/hkex-cmdb-omp.XXXXXX")
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "$DIST_DIR"
gtar -xzf "$SOURCE_TAR" -C "$WORK_DIR"
STAGE_DIR="$WORK_DIR/hkexCmdbSync"

if [ ! -d "$STAGE_DIR" ]; then
    echo "Assembly package root hkexCmdbSync is missing" >&2
    exit 1
fi
mkdir -p "$STAGE_DIR/scripts"

sed -e "s/@CMDB_VERSION@/$VERSION/g" \
    -e "s/@OMP_PRODUCT_NAME@/$PRODUCT_NAME/g" \
    -e "s/@OMP_PRODUCT_VERSION@/$PRODUCT_VERSION/g" \
    "$SERVER_DIR/conf/hkexCmdbSync.yaml" > "$STAGE_DIR/hkexCmdbSync.yaml"
cp "$SERVER_DIR/scripts/"*.py "$STAGE_DIR/scripts/"
chmod 755 "$STAGE_DIR/bin/"* "$STAGE_DIR/scripts/"*.py

{
    echo "service-name=hkexCmdbSync"
    echo "service-version=$VERSION"
    echo "product-name=$PRODUCT_NAME"
    echo "product-version=$PRODUCT_VERSION"
    echo "mandatory-dependent-services=jdk17,nacos"
} > "$STAGE_DIR/service.properties"

# 运行时文件和本地环境配置不得进入应用商店包。
rm -f "$STAGE_DIR/conf/nacos.params" "$STAGE_DIR/conf/omp.env" \
    "$STAGE_DIR/conf/application-dev.properties" \
    "$STAGE_DIR/conf/application-prod.properties" \
    "$STAGE_DIR/conf/application-local.properties"

if grep -R -n -E 'BEGIN (RSA |EC )?PRIVATE KEY|gcpCredentialsAesKey[[:space:]]*=' \
        "$STAGE_DIR" >/dev/null 2>&1; then
    echo "Sensitive key material detected in OMP package staging directory" >&2
    exit 1
fi
if grep -R -n '@CMDB_VERSION@\|@OMP_PRODUCT_' "$STAGE_DIR" >/dev/null 2>&1; then
    echo "Unresolved OMP package placeholder detected" >&2
    exit 1
fi

OUTPUT="$DIST_DIR/hkexCmdbSync-$VERSION.tar.gz"
gtar --owner=0 --group=0 --numeric-owner -czf "$OUTPUT" -C "$WORK_DIR" hkexCmdbSync
printf 'Created OMP service package:\n  %s\n' "$OUTPUT"
printf 'Independent product ownership:\n  %s %s\n' "$PRODUCT_NAME" "$PRODUCT_VERSION"
printf 'Register that product in OMP before uploading the service package.\n'
