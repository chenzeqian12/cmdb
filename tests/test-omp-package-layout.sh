#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
SERVER_DIR="$PROJECT_DIR/hkex-cmdb-sync-server"
YAML="$SERVER_DIR/conf/hkexCmdbSync.yaml"

for path in \
  "$YAML" \
  "$SERVER_DIR/bin/hkexCmdbSync" \
  "$SERVER_DIR/bin/java17.sh" \
  "$SERVER_DIR/bin/publishCmdbConfig" \
  "$SERVER_DIR/scripts/omp_common.py" \
  "$SERVER_DIR/scripts/install.py" \
  "$SERVER_DIR/scripts/upgrade.py" \
  "$SERVER_DIR/scripts/rollback.py" \
  "$PROJECT_DIR/omp-package.sh"; do
  [ -f "$path" ] || { echo "missing required file: $path" >&2; exit 1; }
done

grep -Eq '^kind:[[:space:]]*service$' "$YAML"
grep -Eq '^name:[[:space:]]*hkexCmdbSync$' "$YAML"
grep -Fq '  name: ${omp.product.name}' "$YAML"
grep -Fq 'version: ${project.version}' "$YAML"
python3 -m unittest discover -s "$PROJECT_DIR/tests" -p 'test_omp_product_package.py'
grep -Eq '^level:[[:space:]]*0$' "$YAML"
grep -Eq '^[[:space:]]*-[[:space:]]*name:[[:space:]]*jdk17$' "$YAML"
grep -Eq '^[[:space:]]*-[[:space:]]*name:[[:space:]]*nacos$' "$YAML"

# 禁止把 CMDB 归到已有产品名下。
if grep -Eq '^[[:space:]]*name:[[:space:]]*(docc|douc|doop|gateway|docpM)$' "$YAML"; then
  echo "CMDB OMP product ownership conflicts with an existing product" >&2
  exit 1
fi

# dependencies 下的每个条目都必须紧跟 version，避免 OMP YAML 校验失败。
awk '
  /^dependencies:/ { inside=1; next }
  inside && /^[^[:space:]]/ { inside=0 }
  inside && /^[[:space:]]*-[[:space:]]*name:/ {
    if (waiting) exit 1
    waiting=1
    next
  }
  inside && waiting && /^[[:space:]]*version:/ { waiting=0 }
  END { if (waiting) exit 1 }
' "$YAML"

grep -Eq 'OMP_APP_HOME' "$SERVER_DIR/bin/hkexCmdbSync"
grep -Eq 'CW_NACOS_PASSWORD' "$SERVER_DIR/scripts/omp_common.py"
grep -Eq 'JAVA_17_HOME' "$SERVER_DIR/bin/java17.sh"
grep -Eq 'resolve_java17' "$SERVER_DIR/bin/publishCmdbConfig"

printf 'CMDB OMP package layout: OK\n'
