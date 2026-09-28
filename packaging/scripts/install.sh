#!/usr/bin/env sh
set -eu

BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
VERSION=${VERSION:-1.0.0-SNAPSHOT}
AGENT_NAME=${AGENT_NAME:-hkexcmdbsync}
INSTALL_ROOT=/opt/cloudwise/omniagent/agents
FORCE=0
OMNIAGENT_META="/opt/cloudwise/omniagent/meta"
INSTALL_PARAMS="$OMNIAGENT_META/installParams"

DAEMON_PATH_ALIAS_ROOT=""
INSTALL_USER=""
INSTALL_GROUP=""
RUN_USER=""
RUN_GROUP=""

# 默认安装/运行用户（与 CDC 的 BRAND_AGENT_DEFAULT_USER_AND_GROUP_NAME 对齐）：
# 安装者通常是 root，装完文件统一 chown 给该用户；服务以该用户运行。
DEFAULT_INSTALL_USER="cloudwise"
DEFAULT_INSTALL_GROUP="cloudwise"

# 取平台守护进程 omniagentd 的运行用户：agent 应与它同用户，
# 否则平台（以该用户）停不掉 agent 进程，升级会失败。
detect_daemon_user() {
	_dpid=$(pgrep -x omniagentd 2>/dev/null | head -1)
	[ -n "$_dpid" ] || _dpid=$(pgrep -f 'omniagent-os' 2>/dev/null | head -1)
	[ -n "$_dpid" ] || return 1
	_du=$(ps -o user= -p "$_dpid" 2>/dev/null | tr -d ' ')
	[ -n "$_du" ] || return 1
	printf '%s' "$_du"
}

usage() {
	cat <<'EOF'
usage: install.sh [INSTALL_ROOT] [--force|-f] [--help|-h] [--allow-downgrade]

  INSTALL_ROOT         install root path
                       (default: /opt/cloudwise/omniagent/agents)
  --force, -f          overwrite an existing version of the same VERSION
                       (idempotent cleanup is also performed automatically; --force is rarely needed)
  --allow-downgrade    install even if the incoming VERSION is LOWER than the currently
                       installed one. By default the installer REFUSES to downgrade, mirroring
                       docc-server's service.properties check (gaia-server/scripts/upgrade.py).
  --help, -h           show this message and exit

environment variables:
  VERSION              incoming version string (e.g. 1.1.25; default: 1.0.0-SNAPSHOT)
  AGENT_NAME           agent name (default: hkexcmdbsync)
  PARAM_JSON_CONF      platform-supplied application.yml content (JSON or YAML).
                       If unset, the built-in src/main/resources/application.yml is used.
  MERGE_STRATEGY       how to merge old application.yml (kept on upgrade) with the new one:
                         none   = use the new config as-is, drop old keys (default; safe)
                         union  = keep old keys that are absent from the new config
                         update = same as 'union' for now (reserved for deep-merge later)
                       Default: none. Set to 'union' on routine upgrades to preserve operator
                       tweaks (e.g. a hand-edited douc.dry-run).

examples:
  sh install.sh
  sh install.sh --force
  sh install.sh /custom/install/root --force
  MERGE_STRATEGY=union sh install.sh           # upgrade while preserving custom config keys

behavior:
  This installer is idempotent. Re-running it (same or different VERSION) will:
    0. REFUSE to downgrade (new VERSION < existing) unless --allow-downgrade is set
    1. stop the currently running agent (if any)
    2. remove the target version directory (kept as a rollback backup when it existed)
    3. reinstall from scratch
  This avoids the "The same type of Agent is already installed." error from omniagentd
  when reinstalling with the same version.
EOF
}

ALLOW_DOWNGRADE=0
for arg in "$@"; do
	case "$arg" in
		*=*) ;;
		--force|-f) FORCE=1 ;;
		--no-force) FORCE=0 ;;
		--allow-downgrade) ALLOW_DOWNGRADE=1 ;;
		--help|-h) usage; exit 0 ;;
		*) INSTALL_ROOT="$arg" ;;
	esac
done

# MERGE_STRATEGY 默认 none；其他合法值 union / update
MERGE_STRATEGY=${MERGE_STRATEGY:-none}

# ═══════════════════════════════════════════════════════════════════════
# 工具函数（必须在版本校验之前定义）
# ═══════════════════════════════════════════════════════════════════════
LOG_FILE="/tmp/hkexcmdbsync-install-$$.log"
: > "$LOG_FILE" 2>/dev/null || LOG_FILE=""
STEP_NO=0

die() {
	# 1) 写 LOG_FILE（完整调用链 + 错误）
	[ -n "$LOG_FILE" ] && printf '%s [ERROR] fail: %s\n' "$(date +%H:%M:%S)" "$*" >> "$LOG_FILE"
	# 2) 写 stderr（omniagentd 日志能看到）
	printf 'fail: %s\n' "$*" >&2
	# 3) 写 stdout（DOCC 平台任务结果能看到）
	printf 'fail: %s\n' "$*"
	# 4) cat 整个 LOG_FILE 到 stdout，让 platform 能直接看到完整调用链
	#    （之前 LOG_FILE 写 /tmp，平台拿不到 → 排查只能 SSH）
	if [ -n "$LOG_FILE" ] && [ -f "$LOG_FILE" ]; then
		# printf '--- ...' 时字符串以 --- 开头会被某些 shell 当成选项；用 -- 终结
		printf '\n%s\n' "-- install log ($LOG_FILE) --"
		cat "$LOG_FILE"
		printf '%s\n' "-- end of log --"
	fi
	exit 1
}

log_step() {
	STEP_NO=$((STEP_NO + 1))
	[ -n "$LOG_FILE" ] && printf '%s [STEP %s] %s\n' "$(date +%H:%M:%S)" "$STEP_NO" "$*" >> "$LOG_FILE"
	printf '%s\n' "$*"
}

log_info() {
	[ -n "$LOG_FILE" ] && printf '%s [INFO] %s\n' "$(date +%H:%M:%S)" "$*" >> "$LOG_FILE"
	# log_info 不直接 stdout —— 避免被 DOCC 平台把进度信息误判成 task 输出
}

log_warn() {
	[ -n "$LOG_FILE" ] && printf '%s [WARN] %s\n' "$(date +%H:%M:%S)" "$*" >> "$LOG_FILE"
	# WARN 也写 stdout，DOCC 平台任务成功时也能看到
	printf 'WARN: %s\n' "$*"
}

validate_path_component() {
	_component_name=$1
	_component_value=$2
	case "$_component_value" in
		''|.|..|*/*|*\\*) die "invalid $_component_name: '$_component_value'" ;;
		*[!A-Za-z0-9._-]*) die "invalid $_component_name: '$_component_value'" ;;
	esac
}

validate_path_component VERSION "$VERSION"
validate_path_component AGENT_NAME "$AGENT_NAME"

resolve_install_identity() {
	# 安装者通常是 root（不再拒绝 root），文件随后统一 chown 给运行用户。
	# 运行用户优先级：PARAM_USER/PARAM_GROUP（平台可传 USER=/GROUP=）
	#                → 守护进程 omniagentd 的用户
	#                → 默认 cloudwise
	# 关键：必须与守护进程同用户 —— 平台以 daemon 用户执行 agentctl start/stop，
	# 若 agent 以别的用户运行，daemon 停不掉它（EPERM），升级就会卡在"停 agent"。
	INSTALL_USER=${PARAM_USER:-}
	INSTALL_GROUP=${PARAM_GROUP:-}

	if [ -z "$INSTALL_USER" ]; then
		INSTALL_USER=$(detect_daemon_user 2>/dev/null || true)
		[ -n "$INSTALL_USER" ] && log_info "install user from omniagentd: $INSTALL_USER"
	fi
	if [ -z "$INSTALL_USER" ]; then
		INSTALL_USER="$DEFAULT_INSTALL_USER"
	fi
	if [ -z "$INSTALL_GROUP" ]; then
		INSTALL_GROUP=$(id -gn "$INSTALL_USER" 2>/dev/null || true)
	fi
	if [ -z "$INSTALL_GROUP" ]; then
		INSTALL_GROUP="$DEFAULT_INSTALL_GROUP"
	fi

	# 仅允许安全账户名，避免写入后续脚本解析时出现注入风险。
	printf '%s' "$INSTALL_USER" | grep -Eq '^[A-Za-z_][A-Za-z0-9_.-]*[$]?$' || die "invalid install user: '$INSTALL_USER'"
	printf '%s' "$INSTALL_GROUP" | grep -Eq '^[A-Za-z_][A-Za-z0-9_.-]*[$]?$' || die "invalid install group: '$INSTALL_GROUP'"
	if ! id -u "$INSTALL_USER" >/dev/null 2>&1; then
		die "install user does not exist: '$INSTALL_USER'. Use --user=<existing user> (wrapper) or create it."
	fi
	if ! id -Gn "$INSTALL_USER" 2>/dev/null | tr ' ' '\n' | grep -Fx "$INSTALL_GROUP" >/dev/null 2>&1; then
		die "install group '$INSTALL_GROUP' is not assigned to user '$INSTALL_USER'"
	fi

	# 运行身份：显式 RUN_USER/RUN_GROUP 优先，否则跟随安装身份。
	# 安装产物的属主（含软链）统一按运行身份设置，避免"安装用户 ≠ 运行用户"
	# 时出现 current 属主与内部文件属主不一致、服务写不了日志的问题。
	RUN_USER=${RUN_USER:-$INSTALL_USER}
	RUN_GROUP=${RUN_GROUP:-$INSTALL_GROUP}
	printf '%s' "$RUN_USER" | grep -Eq '^[A-Za-z_][A-Za-z0-9_.-]*[$]?$' || die "invalid run user: '$RUN_USER'"
	printf '%s' "$RUN_GROUP" | grep -Eq '^[A-Za-z_][A-Za-z0-9_.-]*[$]?$' || die "invalid run group: '$RUN_GROUP'"
	id -u "$RUN_USER" >/dev/null 2>&1 || die "run user does not exist: '$RUN_USER'"
	if ! id -Gn "$RUN_USER" 2>/dev/null | tr ' ' '\n' | grep -Fx "$RUN_GROUP" >/dev/null 2>&1; then
		die "run group '$RUN_GROUP' is not assigned to user '$RUN_USER'"
	fi

	log_info "install identity resolved: ${INSTALL_USER}:${INSTALL_GROUP} (run identity: ${RUN_USER}:${RUN_GROUP})"
}

ensure_daemon_path_alias() {
	_instance_id=$1
	[ -n "$_instance_id" ] || return 0
	_case_safe=$(printf '%s' "$_instance_id" | tr -cd 'a-zA-Z0-9')
	[ -n "$_case_safe" ] || return 0
	_alias_path="$INSTALL_ROOT/${AGENT_NAME}_${_case_safe}"
	DAEMON_PATH_ALIAS_ROOT="$_alias_path"

	if [ -e "$_alias_path" ] && [ ! -L "$_alias_path" ]; then
		log_warn "daemon alias path exists but is not symlink: $_alias_path (skip)"
		return 0
	fi

	ln -sfn "$AGENT_ROOT" "$_alias_path"
	chown -h "$RUN_USER:$RUN_GROUP" "$_alias_path" 2>/dev/null || true
	log_info "ensured daemon path alias: $_alias_path -> $AGENT_ROOT"
}

# ── 校验 jar ──
# 注意：必须 set +e 包起来，否则 set -o pipefail + find 在 target/ 不存在时
# 退出 1，会让 set -e 触发 exit，die 没机会执行（LOG_FILE 0 字节）。
# 用 2>/dev/null 抑制 find 的 "No such file or directory" stderr。
set +e
APP_JAR=$(find "$BASE_DIR/../target" -maxdepth 1 -type f -name 'hkex-cmdb-sync-server-*.jar' ! -name '*sources.jar' ! -name '*javadoc.jar' 2>/dev/null | head -n 1)
set -e
if [ -z "$APP_JAR" ] || [ ! -f "$APP_JAR" ]; then
	die "jar not found under target ($BASE_DIR/../target). Build the project before install."
fi

resolve_install_identity

# ═══════════════════════════════════════════════════════════════
# 路径常量
# ═══════════════════════════════════════════════════════════════
AGENT_ROOT="$INSTALL_ROOT/$AGENT_NAME"
TARGET_DIR="$AGENT_ROOT/$VERSION"
CURRENT_LINK="$AGENT_ROOT/current"
CURRENT_AGENTCTL="$CURRENT_LINK/bin/agentctl"
BIN_DIR="$TARGET_DIR/bin"
CONF_DIR="$TARGET_DIR/conf"
LIB_DIR="$TARGET_DIR/lib"
LOG_DIR="$TARGET_DIR/logs"
RUN_DIR="$TARGET_DIR/run"
SCRIPTS_DIR="$TARGET_DIR/scripts"
JRE_DIR="$TARGET_DIR/jre"

# 旧 application.yml 在 cleanup 之前必须先备份到 /tmp，给 MERGE_STRATEGY 用
OLD_CONFIG_FILE="/tmp/hkexcmdbsync-prev-application.yml.$$"
UPGRADE_BACKUP_DIR=""
PREVIOUS_CURRENT_TARGET=""
# 并发安装/卸载保护（参考 CDC 的安装锁）
AGENT_LOCK_FILE="$INSTALL_ROOT/${AGENT_NAME}.lock"

upgrade_cleanup() {
	_status=$1
	if [ "$_status" -ne 0 ]; then
		if [ -n "$UPGRADE_BACKUP_DIR" ] && [ -d "$UPGRADE_BACKUP_DIR" ]; then
			rm -rf "$TARGET_DIR"
			mv "$UPGRADE_BACKUP_DIR" "$TARGET_DIR" || true
		elif [ -n "$PREVIOUS_CURRENT_TARGET" ] && [ "$TARGET_DIR" != "$CURRENT_LINK/$PREVIOUS_CURRENT_TARGET" ]; then
			rm -rf "$TARGET_DIR"
		fi
		if [ -n "$PREVIOUS_CURRENT_TARGET" ]; then
			ln -sfn "$PREVIOUS_CURRENT_TARGET" "$CURRENT_LINK"
		fi
	fi
	[ -n "$UPGRADE_BACKUP_DIR" ] && rm -rf "$UPGRADE_BACKUP_DIR"
	rm -f "$OLD_CONFIG_FILE"
	rm -f "$AGENT_LOCK_FILE" 2>/dev/null || true
	trap - EXIT
	exit "$_status"
}
trap 'upgrade_cleanup "$?"' EXIT

# ── 安装锁：避免并发安装/卸载互相踩（带陈旧锁检测）──
# 安装根可能还不存在（首次安装 / 手动装到自定义路径），先建出来再写锁，
# 否则重定向会因目录缺失直接失败。
mkdir -p "$INSTALL_ROOT" 2>/dev/null || true
if [ -f "$AGENT_LOCK_FILE" ]; then
	_lock_pid=$(head -n 1 "$AGENT_LOCK_FILE" 2>/dev/null || true)
	if [ -n "$_lock_pid" ] && kill -0 "$_lock_pid" 2>/dev/null; then
		die "another ${AGENT_NAME} install/uninstall is running (pid $_lock_pid, lock: $AGENT_LOCK_FILE)"
	fi
	log_warn "removing stale install lock (pid '${_lock_pid:-unknown}' is not running)"
	rm -f "$AGENT_LOCK_FILE" 2>/dev/null || true
fi
printf '%s\n%s\n' "$$" "$0" > "$AGENT_LOCK_FILE" 2>/dev/null || true
log_info "acquired install lock: $AGENT_LOCK_FILE"

# ═══════════════════════════════════════════════════════════════
# 校验 MERGE_STRATEGY
# ═══════════════════════════════════════════════════════════════
case "$MERGE_STRATEGY" in
	none|union|update) ;;
	*) die "invalid MERGE_STRATEGY='$MERGE_STRATEGY' (expected: none|union|update)" ;;
esac

# ═══════════════════════════════════════════════════════════════
# 语义化版本比较（仿 docc upgrade.py 的版本检查，但用 shell 实现）
# 返回值写入 INSTALL_VERSION_CMP：
#   -1  新版 < 老版（应拒绝）
#    0  新版 == 老版
#    1  新版 > 老版
# 比较规则：按 . 拆段，每段按数字比；段数不足的补 0；忽略 -SNAPSHOT 后缀
# ═══════════════════════════════════════════════════════════════
normalize_version() {
	_v=$1
	# 去掉 -SNAPSHOT / -RC1 之类后缀
	_v=${_v%%-*}
	# 1.2.3.4 → 1 2 3 4
	printf '%s' "$_v" | tr '.' ' '
}

version_compare() {
	_new=$1
	_old=$2
	# 老版本为空/缺失：当作 0.0.0（首次安装）
	[ -z "$_old" ] && _old="0.0.0"
	_new_norm=$(normalize_version "$_new")
	_old_norm=$(normalize_version "$_old")
	# 短的补 0
	while [ "$(printf '%s' "$_new_norm" | wc -w)" -gt "$(printf '%s' "$_old_norm" | wc -w)" ]; do
		_old_norm="$_old_norm 0"
	done
	while [ "$(printf '%s' "$_old_norm" | wc -w)" -gt "$(printf '%s' "$_new_norm" | wc -w)" ]; do
		_new_norm="$_new_norm 0"
	done
	_i=1
	_new_wc=$(printf '%s' "$_new_norm" | wc -w | tr -d ' ')
	_old_wc=$(printf '%s' "$_old_norm" | wc -w | tr -d ' ')
	while [ "$_i" -le "$_new_wc" ] && [ "$_i" -le "$_old_wc" ]; do
		_n=$(printf '%s' "$_new_norm" | awk -v i="$_i" '{print $i}')
		_o=$(printf '%s' "$_old_norm" | awk -v i="$_i" '{print $i}')
		# 非数字段（少见）按 0 处理
		case "$_n" in ''|*[!0-9]*) _n=0;; esac
		case "$_o" in ''|*[!0-9]*) _o=0;; esac
		if [ "$_n" -lt "$_o" ]; then
			INSTALL_VERSION_CMP=-1
			return
		elif [ "$_n" -gt "$_o" ]; then
			INSTALL_VERSION_CMP=1
			return
		fi
		_i=$((_i + 1))
	done
	INSTALL_VERSION_CMP=0
}

# ═══════════════════════════════════════════════════════════════
# 读取并备份老安装信息（在 idempotent cleanup 之前）
# ═══════════════════════════════════════════════════════════════
EXISTING_VERSION=""
EXISTING_CONFIG=""
EXISTING_INSTANCE_ID=""
if [ -L "$CURRENT_LINK" ] && [ -f "$CURRENT_LINK/installer.version" ]; then
	PREVIOUS_CURRENT_TARGET=$(readlink "$CURRENT_LINK" 2>/dev/null || true)
	EXISTING_VERSION=$(cat "$CURRENT_LINK/installer.version" 2>/dev/null || true)
	EXISTING_CONFIG="$CURRENT_LINK/conf/application.yml"
	log_info "detected existing installation version='$EXISTING_VERSION' at $CURRENT_LINK"
fi
if [ -f "$AGENT_ROOT/.instanceId" ]; then
	EXISTING_INSTANCE_ID=$(tr -d '\r\n' < "$AGENT_ROOT/.instanceId" 2>/dev/null || true)
	log_info "detected existing .instanceId='$EXISTING_INSTANCE_ID'"
fi

# ═══════════════════════════════════════════════════════════════
# 拒绝降级（仿 docc upgrade.py:248-262 的 service.properties 检查）
# 触发条件：新 VERSION < 老 EXISTING_VERSION（按 . 段数值比较）
# 解除：--allow-downgrade
# ═══════════════════════════════════════════════════════════════
if [ -n "$EXISTING_VERSION" ] && [ "$ALLOW_DOWNGRADE" -eq 0 ]; then
	version_compare "$VERSION" "$EXISTING_VERSION"
	if [ "${INSTALL_VERSION_CMP:-0}" -lt 0 ]; then
		die "refusing to downgrade: incoming version '$VERSION' is LOWER than installed '$EXISTING_VERSION'. Pass --allow-downgrade to override (rollback scenario)."
	fi
fi

if [ -d "$TARGET_DIR" ] && [ "$FORCE" -eq 0 ]; then
	die "target version already exists: $TARGET_DIR. Use --force to reinstall this version."
fi

# ═══════════════════════════════════════════════════════════════
# 把老 application.yml 备份到 /tmp，给 MERGE_STRATEGY 用
# ═══════════════════════════════════════════════════════════════
if [ -n "$EXISTING_VERSION" ] && [ "$MERGE_STRATEGY" != "none" ] && [ -f "$EXISTING_CONFIG" ]; then
	cp "$EXISTING_CONFIG" "$OLD_CONFIG_FILE"
	log_info "saved old application.yml for $MERGE_STRATEGY merge: $EXISTING_VERSION -> $OLD_CONFIG_FILE"
fi

# ═══════════════════════════════════════════════════════════════
# Versioned upgrade: 保留旧版本目录，只停止当前版本并切换 current。
# 不删除整个 Agent 根目录，便于升级失败时回滚到旧版本。
# ═══════════════════════════════════════════════════════════════
if [ -d "$AGENT_ROOT" ] || [ -f "$AGENT_ROOT/.instanceId" ] || [ -L "$CURRENT_LINK" ]; then
	log_step "versioned upgrade: existing installation detected at $AGENT_ROOT"

	# 1. 停掉老 agent（如果 agentctl 还在 + 进程还活着）
	if [ -x "$CURRENT_AGENTCTL" ]; then
		if "$CURRENT_AGENTCTL" status 2>/dev/null | grep -F 'is running' >/dev/null 2>&1; then
			log_info "stopping running agent via $CURRENT_AGENTCTL"
			"$CURRENT_AGENTCTL" stop 2>/dev/null || log_warn "agentctl stop returned non-zero (continuing)"
		fi
	fi

	# 兜底：扫描进程列表，杀掉本 agent 的 java 进程（覆盖 agentctl 状态丢失/PID 漂移）。
	# 注意：进程 argv0 是 "hkexcmdbsync"（agentctl 用 exec -a 设置），命令行里没有 "java"
	# 字样，故不能按 "java" 匹配；用 -Dhkex.app-home=<AGENT_ROOT> + -Dhkex.main-class=
	# 定位，并对 current / 真实版本目录两种启动路径都成立。
	for _pid in $(ps -ef \
		| grep -F -- "-Dhkex.app-home=$AGENT_ROOT" \
		| grep -F -- "-Dhkex.main-class=" \
		| grep -v grep \
		| grep -vE 'sudo |nohup |bash -c' \
		| awk '{print $2}'); do
		kill -TERM "$_pid" 2>/dev/null || true
		log_info "sent SIGTERM to stale agent pid $_pid"
	done
	sleep 1
	for _pid in $(ps -ef \
		| grep -F -- "-Dhkex.app-home=$AGENT_ROOT" \
		| grep -F -- "-Dhkex.main-class=" \
		| grep -v grep \
		| grep -vE 'sudo |nohup |bash -c' \
		| awk '{print $2}'); do
		kill -KILL "$_pid" 2>/dev/null || true
		log_info "sent SIGKILL to stubborn agent pid $_pid"
	done

	# 同版本只有显式 --force 才覆盖目标版本目录；不同版本保留旧版本。
	if [ -d "$TARGET_DIR" ] && [ "$FORCE" -eq 1 ]; then
		UPGRADE_BACKUP_DIR="$AGENT_ROOT/.upgrade-backup.$$"
		mv "$TARGET_DIR" "$UPGRADE_BACKUP_DIR"
		log_info "removed existing target version for force reinstall: $TARGET_DIR"
	fi
fi

# ═══════════════════════════════════════════════════════════════════════
# 主安装流程
# ═══════════════════════════════════════════════════════════════════════
log_step "creating directories under $TARGET_DIR"
mkdir -p "$BIN_DIR" "$CONF_DIR" "$LIB_DIR" "$LOG_DIR" "$RUN_DIR" "$SCRIPTS_DIR"

log_step "copying agent binaries"
cp "$BASE_DIR/bin/agentctl" "$BIN_DIR/agentctl" || die "copy agentctl"
cp "$BASE_DIR/run.sh" "$BIN_DIR/run.sh" || die "copy run.sh"
cp "$BASE_DIR/scripts/uninstall.sh" "$TARGET_DIR/uninstall.sh" || die "copy uninstall.sh"
# 本工程是 thin jar（MANIFEST 无 Main-Class），启动走 `-classpath 'lib/*' <主类>`，
# 所以必须把主 jar 和全部依赖 jar 一起装进去，不能只拷主 jar。
[ -d "$BASE_DIR/../lib" ] || die "lib/ not found in package ($BASE_DIR/../lib)"
cp "$BASE_DIR/../lib/"*.jar "$LIB_DIR/" || die "copy lib jars"
[ -f "$LIB_DIR/$(basename "$APP_JAR")" ] || die "main jar missing after copy: $LIB_DIR/$(basename "$APP_JAR")"
log_info "copied agentctl / run.sh / uninstall.sh + $(ls -1 "$LIB_DIR" | wc -l | tr -d ' ') jars into lib/"

# ── 写 application.yml ──
# 优先级：$PARAM_JSON_CONF（平台下发）> 内置默认 application.yml
# $PARAM_JSON_CONF 来自 install_temp.sh 从 ----PARAMETERS 段解析，
# 或者调用方直接以环境变量形式传入。
#
# 内容可以是 JSON 也可以是 YAML —— 这里只做"非空 + 看起来像配置"的兜底校验，
# 真正的合法性由 Spring 启动时判定（解析失败会带行号报错，比这里猜得更准）。
#
# 如果 MERGE_STRATEGY != none 且存在老配置（升级场景），先写新配置，
# 再把老配置里"新配置没有的 top-level key"追加到末尾 —— 这是 docc
# (gaia-server/scripts/upgrade.py + core.sh) 思想的轻量化实现。
# 注意：YAML 深合并太复杂，这里只做 top-level shallow merge；运维要保留
# 嵌套字段时建议走 PARAM_JSON_CONF 平台下发路径。
merge_yaml_top_level() {
	_old=$1
	_new=$2

	# 提取老配置的 top-level key 列表。规则：行首非空白/非注释、且含 ':'。
	# 用 grep 而不是 awk -F:，避免 key 含 ':' 时的截断。
	_old_keys=$(grep -E '^[^[:space:]#][^:]*:' "$_old" 2>/dev/null \
		| sed -E 's/^([^:]+):.*/\1/' \
		| sort -u)
	if [ -z "$_old_keys" ]; then
		return 0
	fi

	# 用 awk 把老配置切成 top-level key 块，按 key 输出对应的整段
	_extract_block() {
		awk -v target="$1" '
			$0 ~ "^[^[:space:]#][^:]*:" {
				if (current_key == target) { in_target = 0 }
				current_key = $0
				sub(/:.*/, "", current_key)
				if (current_key == target) { in_target = 1; print; next }
			}
			in_target == 1 { print }
		' "$_old"
	}

	for k in $_old_keys; do
		# _old_keys 行可能含 shell 元字符（极少见），用 case 包裹一下
		case "$k" in *[!a-zA-Z0-9._-]*) continue;; esac
		# 新配置里已经有这个 key → 跳过（保留新值）
		if grep -qE "^${k}:" "$_new" 2>/dev/null; then
			log_info "merge: skip '$k' (new config already has it)"
			continue
		fi
		# 新配置里没有 → 从老配置里抽整段追加
		_block=$(_extract_block "$k")
		if [ -n "$_block" ]; then
			printf '\n%s\n' "$_block" >> "$_new"
			log_info "merge: kept old top-level key '$k' from $EXISTING_VERSION"
		fi
	done
}

log_step "writing application.yml (MERGE_STRATEGY=$MERGE_STRATEGY)"
if [ -n "${PARAM_JSON_CONF:-}" ]; then
	# 兜底校验：既不像 JSON 也不像 YAML 的空白/垃圾内容直接拦下，
	# 免得写进去之后 Spring 报一个不知所云的解析错误。
	case "$PARAM_JSON_CONF" in
		*:*) ;;
		*) die "PARAM_JSON_CONF is set but does not look like JSON/YAML (no ':' found)" ;;
	esac
	printf '%s' "$PARAM_JSON_CONF" > "$CONF_DIR/application.yml" \
		|| die "write application.yml from PARAM_JSON_CONF"
	[ -s "$CONF_DIR/application.yml" ] || die "application.yml is empty after PARAM_JSON_CONF write"
	log_info "wrote application.yml from PARAM_JSON_CONF (${#PARAM_JSON_CONF} bytes, $CONF_DIR/application.yml)"
else
	cp "$BASE_DIR/../src/main/resources/application.yml" "$CONF_DIR/application.yml" \
		|| die "copy application.yml"
	[ -s "$CONF_DIR/application.yml" ] || die "application.yml is empty after copy (source file is empty or unreadable)"
	log_info "wrote application.yml from built-in default (no PARAM_JSON_CONF supplied, $CONF_DIR/application.yml)"
fi

# 合并：把老配置里"新配置没有的"top-level key 追加进来
# 仅在升级路径（存在老配置）+ 非 none 策略时执行
if [ -n "$EXISTING_VERSION" ] && [ "$MERGE_STRATEGY" != "none" ] && [ -f "$OLD_CONFIG_FILE" ]; then
	log_step "merging old application.yml from $EXISTING_VERSION (strategy=$MERGE_STRATEGY)"
	merge_yaml_top_level "$OLD_CONFIG_FILE" "$CONF_DIR/application.yml"
	log_info "merge complete -> $CONF_DIR/application.yml"
fi

# 收尾：清掉 /tmp 里的老配置备份
rm -f "$OLD_CONFIG_FILE" 2>/dev/null || true

# ── 链接 / 兜底 JRE ──
if [ -d "$BASE_DIR/jre" ]; then
	rm -rf "$JRE_DIR"
	cp -R "$BASE_DIR/jre" "$JRE_DIR"
fi
log_step "linking JRE"
if [ ! -x "$JRE_DIR/bin/java" ]; then
	mkdir -p "$JRE_DIR/bin"
	java_cmd=""
	if command -v java >/dev/null 2>&1; then
		java_cmd=$(command -v java)
	elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
		java_cmd="$JAVA_HOME/bin/java"
	elif [ -x /data/app/jdk17/bin/java ]; then
		java_cmd=/data/app/jdk17/bin/java
	elif [ -x /usr/local/jdk17/bin/java ]; then
		java_cmd=/usr/local/jdk17/bin/java
	fi
	if [ -n "$java_cmd" ]; then
		ln -sf "$java_cmd" "$JRE_DIR/bin/java"
		log_info "linked system java -> $JRE_DIR/bin/java"
		# 同时确保 /usr/bin/java 存在（系统级兜底）
		if [ ! -x /usr/bin/java ]; then
			ln -sf "$java_cmd" /usr/bin/java 2>/dev/null || true
		fi
	else
		log_warn "java not found. The agent will fail to start without JAVA_HOME or a bundled JRE."
	fi
else
	log_info "jre already has bin/java, skip linking"
fi
printf '%s\n' "$VERSION" > "$TARGET_DIR/installer.version"
cat > "$TARGET_DIR/install.identity" <<- IDENTITY_EOF
INSTALL_USER=$INSTALL_USER
INSTALL_GROUP=$INSTALL_GROUP
RUN_USER=$RUN_USER
RUN_GROUP=$RUN_GROUP
IDENTITY_EOF
chmod +x "$BIN_DIR/agentctl" "$BIN_DIR/run.sh" "$TARGET_DIR/uninstall.sh"
log_info "wrote installer.version/install.identity + chmod +x on bin/scripts"

# ── 创建 agentinfo 描述文件 ──
# 平台插件详情页读这个文件展示用途。
log_step "creating agentinfo/agentinfo.json"
mkdir -p "$TARGET_DIR/agentinfo"
cat > "$TARGET_DIR/agentinfo/agentinfo.json" <<- AGENTINFO_EOF
{
	"function_intr": "HKEX CMDB 同步插件：读取 ServiceNow 里名字以 _SUP 结尾的用户组，在 Cloudwise DOUC 中建出对应的用户组，并按 ServiceNow 的组员关系把用户配进组内。只建组和配人，不建用户。"
}
AGENTINFO_EOF

# ── 注册 agent 到 omniagentd ──
#
# installParams 是平台（底座）用来认「这台装了哪些插件」的注册表。它坏掉，平台就
# 认不出插件，会把安装判为失败 —— 即使插件本身装好且在跑。
#
# 这个文件**经常是脏的**：老版卸载脚本用 sed 删数组元素，删完会留下尾随逗号
# （["cdc","cloudevents"] 删 cloudevents → ["cdc",]），JSON 直接解析就挂。
# 之前的实现（抄自 GCP install.sh）只会往里插字符串，既不检查也不修复，
# 结果是把自己也写进一个坏文件、平台照样认不出。
#
# 所以这里改成：**先解析、修好、再合并、最后校验**，并且强制 644 ——
# 底座 omniagentd 以非 root 用户跑，600 会让它读不到。
update_install_params() {
	_params_file="$1"
	_agent_id="$2"

	if command -v python3 >/dev/null 2>&1; then
		python3 - "$_params_file" "$_agent_id" <<'PYEOF'
import json, os, re, sys

path, agent = sys.argv[1], sys.argv[2]
try:
    raw = open(path).read()
except OSError as e:
    print("WARN: 读不到 %s（%s），跳过注册" % (path, e))
    sys.exit(0)

# 先修历史遗留的尾随逗号，否则 json.loads 直接抛
fixed = re.sub(r',(\s*[\]\}])', r'\1', raw.strip())
try:
    data = json.loads(fixed)
    if not isinstance(data, dict):
        raise ValueError("顶层不是对象")
    if fixed != raw.strip():
        print("WARN: %s 里发现非法尾随逗号（历史遗留），已一并修复" % path)
except ValueError as e:
    print("WARN: %s 不是合法 JSON（%s），重建为空结构" % (path, e))
    data = {}

ids = data.get("agentIds")
if not isinstance(ids, list):
    ids = []
ids = [i for i in ids if isinstance(i, str)]
if agent not in ids:
    ids.insert(0, agent)
data["agentIds"] = ids
# 缺的键补上，别让平台读到 undefined
for k, v in (("configIds", []), ("tagNames", []), ("attributes", {})):
    data.setdefault(k, v)

try:
    with open(path, "w") as f:
        f.write(json.dumps(data, separators=(",", ":")))
except OSError as e:
    print("WARN: 写不了 %s（%s），跳过注册" % (path, e))
    sys.exit(0)

# 644：底座不是 root，600 会让它 Permission denied → 认不出插件
try:
    os.chmod(path, 0o644)
except OSError:
    pass
print("OK %s" % json.dumps(data, separators=(",", ":")))
PYEOF
		_params_rc=$?
		if [ "$_params_rc" -ne 0 ]; then
			log_warn "installParams 注册失败（python3 退出码 $_params_rc）；插件已装好，平台可能认不出它"
			return 0
		fi
		# python 写了才 chown；文件原本的属主可能是 root（平台建的），别乱改
		chmod 644 "$_params_file" 2>/dev/null || true
		log_info "registered $_agent_id in $_params_file"
		return 0
	fi

	# ── 没有 python3 的兜底：至少去掉尾随逗号再插，别把文件写坏 ──
	log_warn "python3 不存在，installParams 走兜底逻辑（只做最小修复）"
	_current=$(cat "$_params_file" 2>/dev/null || echo "")
	if [ -z "$_current" ]; then
		_current='{"agentIds":[],"configIds":[],"tagNames":[],"attributes":{}}'
	fi
	# 去尾随逗号： , 后面紧跟 ] 或 } 的都干掉
	_fixed=$(printf '%s' "$_current" | sed -E 's/,([[:space:]]*[][}])/\1/g')
	case "$_fixed" in
		*'"'"$_agent_id"'"'*) ;;
		*) _fixed=$(printf '%s' "$_fixed" | sed 's|"agentIds":[[:space:]]*\[|"agentIds":["'"$_agent_id"'",|') ;;
	esac
	if printf '%s' "$_fixed" > "$_params_file" 2>/dev/null; then
		chmod 644 "$_params_file" 2>/dev/null || true
		log_info "registered $_agent_id in $_params_file (fallback)"
	else
		log_warn "写不了 $_params_file；插件已装好，平台可能认不出它"
	fi
}

log_step "registering hkexcmdbsync in omniagentd installParams"
# meta 目录由底座 omniagentd 拥有，插件不一定写得进去（手动/离线安装时更是如此）——
# 写不进去就只告警，不能因此让整个安装失败：插件本体已经装好了。
if mkdir -p "$OMNIAGENT_META" 2>/dev/null; then
	if [ ! -f "$INSTALL_PARAMS" ]; then
		printf '%s' '{"agentIds":[],"configIds":[],"tagNames":[],"attributes":{}}' > "$INSTALL_PARAMS" 2>/dev/null \
			&& chown "$INSTALL_USER:$INSTALL_GROUP" "$INSTALL_PARAMS" 2>/dev/null || true
	fi
	update_install_params "$INSTALL_PARAMS" "hkexcmdbsync"
else
	log_warn "cannot access $OMNIAGENT_META (meta dir missing or not writable); the platform will register the agent itself"
fi

# 心跳文件由应用自己按 hkex.heartbeat.* 定频刷新（见 PluginHeartbeatWriter）。
# 这里先建一个空文件，让平台在应用起来之前也能看到「插件目录结构完整」。
touch "$TARGET_DIR/heartbeat" 2>/dev/null || true

ln -sfn "$VERSION" "$CURRENT_LINK"
# chown -R 不会改软链本身，这里显式 -h 修正 current 软链属主，避免"壳"与内容属主不一致。
chown -h "$RUN_USER:$RUN_GROUP" "$CURRENT_LINK" 2>/dev/null || true
log_info "linked current -> $VERSION"

# 兼容 omniagentd 可能下发的后缀路径（如 hkexcmdbsync_<daemonInstanceId>）
# 提前创建别名可避免 create task runner error: agent not extence。
ensure_daemon_path_alias "$EXISTING_INSTANCE_ID"

# ── 修正目录权限：统一归属运行用户（RUN_USER/RUN_GROUP，默认跟随安装身份）──
# 注意：chown -R 不改软链本身，current / daemon 别名已在上方用 chown -h 单独处理。
chown -R "$RUN_USER:$RUN_GROUP" "$AGENT_ROOT" 2>/dev/null || true
log_info "fixed ownership: ${RUN_USER}:${RUN_GROUP} -> $AGENT_ROOT"
# 平台可能以非 root 用户（如 commonuser）执行 agentctl start，
# 因此 logs/ 和 run/ 需要对 other 开放写权限，否则 JVM 启动时写不了日志直接退出。
chmod o+w "$LOG_DIR" "$RUN_DIR" 2>/dev/null || true

# ── 安装/升级完成后自动启动 agent ──
# 说明：历史上 install.sh 只装不启（当时 agentctl 的进程识别不准，install 启动 + omniagentd
# 再启动会双重启动、端口冲突）。现在 is_running/进程匹配已改为路径无关且幂等，
# 重复 start 会直接返回，故恢复"装完即启动"——避免升级后停在"已停止"、还需人工点启动。
# 以 RUN_USER 身份启动，保证运行用户与文件属主一致（不降权模型下，调用者即运行者）。
if [ -x "$CURRENT_AGENTCTL" ]; then
	log_step "starting agent via agentctl (runas=${RUN_USER})"
	_cur_user=$(id -un 2>/dev/null || true)
	if [ "$_cur_user" = "$RUN_USER" ]; then
		"$CURRENT_AGENTCTL" start || log_warn "agentctl start failed; platform may start it later"
	elif [ "$_cur_user" = "root" ] && command -v sudo >/dev/null 2>&1 && sudo -u "$RUN_USER" true 2>/dev/null; then
		sudo -u "$RUN_USER" "$CURRENT_AGENTCTL" start || log_warn "agentctl start failed; platform may start it later"
	else
		log_warn "cannot start agent as '$RUN_USER' from '$_cur_user'; leaving start to platform"
	fi
else
	log_warn "agentctl not found at $CURRENT_AGENTCTL; skip auto start"
fi

log_step "installed $AGENT_NAME v$VERSION into $TARGET_DIR (agent auto-started)"
printf 'install log: %s\n' "$LOG_FILE"
