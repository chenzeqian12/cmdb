#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "$0")" && pwd)
PRODUCT_NAME=${OMP_PRODUCT_NAME:-hkexCmdbSync}
EXPECTED_VERSION=${1:-}

if [ "$#" -gt 1 ] || [[ "$PRODUCT_NAME" == *[!A-Za-z0-9._-]* ]] \
        || [[ "$EXPECTED_VERSION" == *[!0-9.]* ]]; then
    printf '用法: %s [版本号]\n产品名仅允许字母、数字、点、横线和下划线。\n' "$0" >&2
    exit 2
fi

if [ "${OMP_SKIP_BUILD:-0}" != "1" ]; then
    mvn -B -f "$PROJECT_DIR/pom.xml" "-Domp.product.name=$PRODUCT_NAME" package
fi

python3 - "$PROJECT_DIR" "$PRODUCT_NAME" "$EXPECTED_VERSION" <<'PY'
import pathlib
import re
import shutil
import sys
import tarfile

project = pathlib.Path(sys.argv[1])
product = sys.argv[2]
expected_version = sys.argv[3]
service = 'hkexCmdbSync'
target = project / 'hkex-cmdb-sync-server/target'
packages = list(target.glob(product + '-*-install.tar.gz'))
if not packages:
    raise SystemExit('没有 Maven 产品包，请先运行 mvn package')
package = max(packages, key=lambda p: p.stat().st_mtime)
with tarfile.open(package) as archive:
    names = [m.name for m in archive.getmembers() if m.isfile()]
    product_yaml = product + '/' + product + '.yaml'
    service_yaml = product + '/' + product + '/' + service + '.yaml'
    nested = [n for n in names if n.endswith('.tar.gz')]
    if len(nested) != 1 or set(names) != {product_yaml, service_yaml, nested[0]}:
        raise SystemExit('产品包必须包含产品定义、内嵌服务包和同名子目录下的服务定义')
    name = pathlib.Path(nested[0]).name
    match = re.fullmatch(r'hkexCmdbSync-(\d+\.\d+\.\d+)-(\d{14})-([0-9a-f]+)\.tar\.gz', name)
    if match is None or (expected_version and match[1] != expected_version):
        raise SystemExit('服务包命名或版本不符合交付约定')
    service_package = target / name
    if not service_package.is_file() or archive.extractfile(nested[0]).read() != service_package.read_bytes():
        raise SystemExit('产品包内嵌服务包与 Maven 服务产物不一致')
destination = project / 'dist/omp'
destination.mkdir(parents=True, exist_ok=True)
for source in (package, service_package):
    shutil.copy2(source, destination / source.name)
    print(destination / source.name)
print('首次安装上传 -install.tar.gz 产品包；独立服务包用于产品登记后的升级。')
PY
