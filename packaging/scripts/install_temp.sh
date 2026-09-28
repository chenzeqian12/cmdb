#!/usr/bin/env sh
set -eu

PACKAGE_NAME="__PACKAGE_NAME__"
AGENT_NAME="__AGENT_NAME__"
INSTALL_VERSION="__INSTALL_VERSION__"
ARCHIVE_MARKER="__HKEX_CMDB_SYNC_ARCHIVE_BELOW__"

# 仿 cdc installer：从文件末尾的 ----PARAMETERS 段读参数，
# 平台生成 .sh 包时把业务配置（PARAM_JSON_CONF / PARAM_USER / PARAM_GROUP）塞到这个段。
PARAMETERS_BEGIN="----PARAMETERS"
PARAMETERS_END="----PARAMETERS--"

SCRIPT_PATH="$0"
WORK_ROOT=${TMPDIR:-/tmp}
WORK_DIR=$(mktemp -d "$WORK_ROOT/${AGENT_NAME}-installer-XXXXXX")
ARCHIVE_PATH="$WORK_DIR/${AGENT_NAME}-${INSTALL_VERSION}.tar.gz"
PAYLOAD_DIR="$WORK_DIR/payload"

# ── 工具：定位 ----PARAMETERS 段的行号 ──
find_section_line() {
	_marker=$1
	awk -v m="$_marker" '$0 == m { print NR; exit }' "$SCRIPT_PATH"
}

# ── 工具：在 ----PARAMETERS 段内读 key=value ──
# 值允许**跨多行**：从 `K=` 那一行开始取，一直到出现下一个 `PARAM_xxx=` 行为止。
# 这是为 application.yml 准备的 —— YAML 本来就是多行；只取第一行的话会被截成
# "spring:"，Spring 拿到必然解析失败。单行 JSON 走同一条路径也不受影响。
read_param() {
	_key=$1
	_begin=$2
	_end=$3
	awk -v k="$_key" -v b="$_begin" -v e="$_end" '
		NR > b && NR < e {
			if (capture) {
				# 下一个参数（PARAM_xxx=）或包内分隔线（-- / ----PARAMETERS--）→ 当前值结束
				if ($0 ~ /^(--|PARAM_[A-Za-z_]+=)/) exit
				print
				next
			}
			if (index($0, k "=") == 1) {
				capture = 1
				sub("^" k "=", "")
				print
			}
		}
	' "$SCRIPT_PATH"
}

cleanup() {
  rm -rf "$WORK_DIR"
}

trap cleanup EXIT INT TERM

extract_archive() {
  marker_line=$(awk -v marker="$ARCHIVE_MARKER" '$0 == marker { print NR + 1; exit }' "$SCRIPT_PATH")
  if [ -z "$marker_line" ]; then
    echo "fail: payload marker not found in $PACKAGE_NAME"
    exit 1
  fi

  tail -n +"$marker_line" "$SCRIPT_PATH" > "$ARCHIVE_PATH"
}

# ── 1) 先看命令行是否显式传入（覆盖包内 PARAMETERS 段） ──
CLI_JSON_CONF=""
CLI_USER=""
CLI_GROUP=""
for arg in "$@"; do
  case "$arg" in
    --jsonConf=*) CLI_JSON_CONF=${arg#--jsonConf=} ;;
    --user=*)     CLI_USER=${arg#--user=} ;;
    --group=*)    CLI_GROUP=${arg#--group=} ;;
    # 平台 daemon（agentdaemon）以 `bash <包> CONFIG_SERVER=.. DATA_SERVER=.. USER=.. GROUP=..`
    # 形式下发任务参数（KEY=VALUE，键大写），这里一并支持，供平台按现场用户安装。
    USER=*)       CLI_USER=${arg#USER=} ;;
    GROUP=*)      CLI_GROUP=${arg#GROUP=} ;;
  esac
done

# ── 2) 解析包内 ----PARAMETERS 段 ──
P_BEGIN=$(find_section_line "$PARAMETERS_BEGIN")
P_END=$(find_section_line "$PARAMETERS_END")
PACKAGED_JSON_CONF=""
PACKAGED_USER=""
PACKAGED_GROUP=""
if [ -n "$P_BEGIN" ] && [ -n "$P_END" ] && [ "$P_END" -gt "$P_BEGIN" ]; then
	PACKAGED_JSON_CONF=$(read_param "PARAM_JSON_CONF" "$P_BEGIN" "$P_END")
	PACKAGED_USER=$(read_param "PARAM_USER" "$P_BEGIN" "$P_END")
	PACKAGED_GROUP=$(read_param "PARAM_GROUP" "$P_BEGIN" "$P_END")
fi

# ── 3) 合并：CLI > 包内 > 空 ──
PARAM_JSON_CONF=${CLI_JSON_CONF:-$PACKAGED_JSON_CONF}
PARAM_USER=${CLI_USER:-$PACKAGED_USER}
PARAM_GROUP=${CLI_GROUP:-$PACKAGED_GROUP}

if [ -n "$PARAM_JSON_CONF" ]; then
	echo "loaded PARAM_JSON_CONF from $([ -n "$CLI_JSON_CONF" ] && echo "CLI" || echo "package PARAMETERS section") (${#PARAM_JSON_CONF} bytes)"
fi

run_install() {
  mkdir -p "$PAYLOAD_DIR"
  tar -xzf "$ARCHIVE_PATH" -C "$PAYLOAD_DIR"
  if [ ! -x "$PAYLOAD_DIR/packaging/scripts/install.sh" ]; then
    echo "fail: install.sh missing from payload"
    exit 1
  fi

  # 安装包由平台 daemon 周期性或按需重推（同一 INSTALL_VERSION 重灌是预期路径），
  # 因此 wrapper 默认向 install.sh 注入 --force，让"已存在则覆盖"成为正常路径。
  # 显式 --no-force 时不注入；--force/-f 已存在时不重复注入以免命令行噪音。
  new_args=""
  found_no_force=0
  found_force=0
  for arg in "$@"; do
    case "$arg" in
      --no-force) found_no_force=1 ;;
      --force|-f) found_force=1 ;;
    esac
  done

  if [ "$found_no_force" = "0" ] && [ "$found_force" = "0" ]; then
    new_args="--force"
  fi

  # 通过环境变量把 PARAMETERS 段透传给 install.sh
  # shellcheck disable=SC2068,SC2086
  VERSION="$INSTALL_VERSION" AGENT_NAME="$AGENT_NAME" \
    PARAM_JSON_CONF="$PARAM_JSON_CONF" \
    PARAM_USER="$PARAM_USER" \
    PARAM_GROUP="$PARAM_GROUP" \
    sh "$PAYLOAD_DIR/packaging/scripts/install.sh" $new_args "$@"
}

extract_archive
run_install "$@"
exit 0
__HKEX_CMDB_SYNC_ARCHIVE_BELOW__
