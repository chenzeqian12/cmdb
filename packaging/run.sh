#!/usr/bin/env sh
set -eu
BASE_DIR=$(cd "$(dirname "$0")" && pwd)
JAVA_HOME=${JAVA_HOME:-}
JAVA_BIN=${JAVA_BIN:-}
CONF_FILE=${CONF_FILE:-}
COMMON_INI=${COMMON_INI:-$BASE_DIR/../../../../conf/common.ini}
NACOS_PARAMS_FILE=${NACOS_PARAMS_FILE:-$BASE_DIR/conf/nacos.params}
# 主类：本工程是 docc 同款 thin jar（MANIFEST 无 Main-Class），
# 所以启动方式是 `-classpath 'lib/*' <主类>`，不能像 GCP 的 event-agent 那样用 `-jar`。
MAIN_CLASS="com.cloudwise.cmdb.CmdbSyncApplication"
APP_JAR=$(find "$BASE_DIR/lib" -maxdepth 1 -type f -name 'hkex-cmdb-sync-server-*.jar' ! -name '*sources.jar' ! -name '*javadoc.jar' 2>/dev/null | head -n 1)

if [ -z "$APP_JAR" ]; then
	APP_JAR=$(find "$BASE_DIR/../lib" -maxdepth 1 -type f -name 'hkex-cmdb-sync-server-*.jar' ! -name '*sources.jar' ! -name '*javadoc.jar' 2>/dev/null | head -n 1)
fi

if [ -z "$APP_JAR" ]; then
	APP_JAR=$(find "$BASE_DIR/../target" -maxdepth 1 -type f -name 'hkex-cmdb-sync-server-*.jar' ! -name '*sources.jar' ! -name '*javadoc.jar' | head -n 1)
fi

# 依赖 jar 与主 jar 同放一个目录（安装态是 lib/）。直接取主 jar 所在的目录，
# 免得再写一遍上面那三种查找路径 —— 写重了就会漂移（踩过：run.sh 在 bin/ 下，
# $BASE_DIR/lib 其实是 bin/lib，不存在，结果 classpath 空 → 找不到主类）。
LIB_DIR=$(dirname "$APP_JAR")

if [ -z "$CONF_FILE" ] && [ -f "$BASE_DIR/../conf/application.yml" ]; then
	CONF_FILE="$BASE_DIR/../conf/application.yml"
fi

# 日志目录：优先取插件运行目录下的 logs/，取不到就回落到相对路径 logs
LOG_DIR=${LOG_DIR:-}
if [ -z "$LOG_DIR" ]; then
	if [ -d "$BASE_DIR/../logs" ]; then
		LOG_DIR="$BASE_DIR/../logs"
	else
		LOG_DIR="$BASE_DIR/logs"
	fi
fi
mkdir -p "$LOG_DIR" 2>/dev/null || true

if [ -z "$JAVA_BIN" ]; then
	REAL_BASE_DIR=$(cd "$(dirname "$0")/.." && pwd -P)
	if [ -x "$REAL_BASE_DIR/jre/bin/java" ]; then
		JAVA_BIN="$REAL_BASE_DIR/jre/bin/java"
	elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
		JAVA_BIN="$JAVA_HOME/bin/java"
	else
		JAVA_BIN=$(command -v java || true)
	fi
fi

if [ ! -x "$JAVA_BIN" ]; then
	echo "fail: java not found. Set JAVA_HOME or JAVA_BIN explicitly."
	exit 1
fi

if [ -z "$APP_JAR" ] || [ ! -f "$APP_JAR" ]; then
	echo "fail: jar not found under lib or target"
	exit 1
fi

read_common_ini_value() {
	key="$1"
	if [ ! -f "$COMMON_INI" ]; then
		echo ""
		return 0
	fi
	awk -F= -v target="$key" '
		/^\[common\]$/ { in_section=1; next }
		/^\[/ && $0 != "[common]" { in_section=0 }
		in_section {
			gsub(/^[[:space:]]+|[[:space:]]+$/, "", $1)
			if ($1 == target) {
				$1=""
				sub(/^=/, "", $0)
				gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
				print $0
				exit
			}
		}
	' "$COMMON_INI"
}

is_json_config() {
	[ -n "$CONF_FILE" ] && [ -f "$CONF_FILE" ] && grep -q '^[[:space:]]*{' "$CONF_FILE" 2>/dev/null
}

read_json_config() {
	tr -d '\n' < "$CONF_FILE"
}

COMMON_CONFIG_SERVER=$(read_common_ini_value config_server)
COMMON_DATA_SERVER=$(read_common_ini_value data_server)
COMMON_FILE_SERVER=$(read_common_ini_value file_server)
COMMON_HOST_IP=$(read_common_ini_value ip)

if [ -f "$NACOS_PARAMS_FILE" ]; then
	# shellcheck disable=SC1090
	. "$NACOS_PARAMS_FILE"
fi
NACOS_SERVER=${CW_NACOS_SERVER:-${CER_NACOS_SERVER:-}}
NACOS_USERNAME=${CW_NACOS_USERNAME:-${CER_NACOS_USERNAME:-}}
NACOS_PASSWORD=${CW_NACOS_PASSWORD:-${CER_NACOS_PASSWORD:-}}
NACOS_NAMESPACE=${CW_NACOS_NAMESPACE:-${CER_NACOS_NAMESPACE:-}}
NACOS_JVM_OPTS=""
if [ -n "$NACOS_SERVER" ]; then
	if [ -z "$NACOS_USERNAME" ] || [ -z "$NACOS_PASSWORD" ] || [ -z "$NACOS_NAMESPACE" ]; then
		echo "fail: Nacos bootstrap parameters are incomplete" >&2
		exit 1
	fi
	NACOS_JVM_OPTS="-DcwNacosServer=$NACOS_SERVER -DcwNacosUserName=$NACOS_USERNAME"
	NACOS_JVM_OPTS="$NACOS_JVM_OPTS -DcwNacosPassword=$NACOS_PASSWORD -DcwNacosNamespace=$NACOS_NAMESPACE"
fi

if [ -n "$CONF_FILE" ]; then
	if is_json_config; then
		SPRING_APPLICATION_JSON=$(read_json_config)
		exec env \
			SPRING_APPLICATION_JSON="$SPRING_APPLICATION_JSON" \
			OMNIAGENT_CONFIG_SERVER="${COMMON_CONFIG_SERVER:-}" \
			OMNIAGENT_DATA_SERVER="${COMMON_DATA_SERVER:-}" \
			OMNIAGENT_FILE_SERVER="${COMMON_FILE_SERVER:-}" \
			OMNIAGENT_HOST_IP="${COMMON_HOST_IP:-}" \
			bash -c 'exec -a hkexcmdbsync "$@"' exec-java "$JAVA_BIN" \
			-Dhkex.config.path="$CONF_FILE" \
			-DcwLogRootPath="$LOG_DIR" \
			-Domniagent.common.ini="$COMMON_INI" \
			-Domniagent.common.config-server="${COMMON_CONFIG_SERVER:-}" \
			-Domniagent.common.data-server="${COMMON_DATA_SERVER:-}" \
			-Domniagent.common.file-server="${COMMON_FILE_SERVER:-}" \
			-Domniagent.common.ip="${COMMON_HOST_IP:-}" \
			$NACOS_JVM_OPTS \
			-classpath "$LIB_DIR/*" "$MAIN_CLASS"
	fi

	exec env \
		OMNIAGENT_CONFIG_SERVER="${COMMON_CONFIG_SERVER:-}" \
		OMNIAGENT_DATA_SERVER="${COMMON_DATA_SERVER:-}" \
		OMNIAGENT_FILE_SERVER="${COMMON_FILE_SERVER:-}" \
		OMNIAGENT_HOST_IP="${COMMON_HOST_IP:-}" \
		bash -c 'exec -a hkexcmdbsync "$@"' exec-java "$JAVA_BIN" \
		-Dhkex.config.path="$CONF_FILE" \
		-DcwLogRootPath="$LOG_DIR" \
		-Domniagent.common.ini="$COMMON_INI" \
		-Domniagent.common.config-server="${COMMON_CONFIG_SERVER:-}" \
		-Domniagent.common.data-server="${COMMON_DATA_SERVER:-}" \
		-Domniagent.common.file-server="${COMMON_FILE_SERVER:-}" \
		-Domniagent.common.ip="${COMMON_HOST_IP:-}" \
		$NACOS_JVM_OPTS \
		-Dspring.config.additional-location="$CONF_FILE" \
		-classpath "$LIB_DIR/*" "$MAIN_CLASS"
fi

exec env \
	OMNIAGENT_CONFIG_SERVER="${COMMON_CONFIG_SERVER:-}" \
	OMNIAGENT_DATA_SERVER="${COMMON_DATA_SERVER:-}" \
	OMNIAGENT_FILE_SERVER="${COMMON_FILE_SERVER:-}" \
	OMNIAGENT_HOST_IP="${COMMON_HOST_IP:-}" \
	bash -c 'exec -a hkexcmdbsync "$@"' exec-java "$JAVA_BIN" \
	-DcwLogRootPath="$LOG_DIR" \
	-Domniagent.common.ini="$COMMON_INI" \
	-Domniagent.common.config-server="${COMMON_CONFIG_SERVER:-}" \
	-Domniagent.common.data-server="${COMMON_DATA_SERVER:-}" \
	-Domniagent.common.file-server="${COMMON_FILE_SERVER:-}" \
	-Domniagent.common.ip="${COMMON_HOST_IP:-}" \
	$NACOS_JVM_OPTS \
	-classpath "$LIB_DIR/*" "$MAIN_CLASS"
