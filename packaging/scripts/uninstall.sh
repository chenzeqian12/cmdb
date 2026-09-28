#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

if [ -d "$SCRIPT_DIR/bin" ] && [ -f "$SCRIPT_DIR/installer.version" ]; then
	TARGET_DIR="$SCRIPT_DIR"
	VERSION=$(cat "$TARGET_DIR/installer.version")
	AGENT_ROOT=$(cd "$TARGET_DIR/.." && pwd)
	AGENT_NAME=$(basename "$AGENT_ROOT")
	CURRENT_LINK="$AGENT_ROOT/current"
	AGENTCTL="$TARGET_DIR/bin/agentctl"

	remove_daemon_aliases() {
		for alias_path in "$INSTALL_ROOT/${AGENT_NAME}_"*; do
			[ -L "$alias_path" ] || continue
			alias_target=$(readlink "$alias_path" 2>/dev/null || true)
			case "$alias_target" in
				"$AGENT_ROOT"|"$AGENT_ROOT/")
					rm -f "$alias_path"
					echo "removed daemon alias: $alias_path"
					;;
			esac
		done
	}
else
	BASE_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
	VERSION=${VERSION:-1.0.0-SNAPSHOT}
	AGENT_NAME=${AGENT_NAME:-hkexcmdbsync}
	INSTALL_ROOT=${1:-/opt/cloudwise/omniagent/agents}
	AGENT_ROOT="$INSTALL_ROOT/$AGENT_NAME"
	TARGET_DIR="$AGENT_ROOT/$VERSION"
	CURRENT_LINK="$AGENT_ROOT/current"
	AGENTCTL="$TARGET_DIR/bin/agentctl"
fi

if [ ! -d "$TARGET_DIR" ]; then
	echo "fail: install target not found: $TARGET_DIR"
	exit 1
fi

# ── 安装锁：与 install.sh 互斥，避免升级/卸载并发（带陈旧锁检测）──
INSTALL_ROOT=${INSTALL_ROOT:-$(cd "$AGENT_ROOT/.." && pwd)}
AGENT_LOCK_FILE="$INSTALL_ROOT/${AGENT_NAME}.lock"
if [ -f "$AGENT_LOCK_FILE" ]; then
	_lock_pid=$(head -n 1 "$AGENT_LOCK_FILE" 2>/dev/null || true)
	if [ -n "$_lock_pid" ] && kill -0 "$_lock_pid" 2>/dev/null; then
		echo "fail: another ${AGENT_NAME} install/uninstall is running (pid $_lock_pid, lock: $AGENT_LOCK_FILE)"
		exit 1
	fi
	echo "warn: removing stale install lock (pid '${_lock_pid:-unknown}' is not running)"
	rm -f "$AGENT_LOCK_FILE" 2>/dev/null || true
fi
printf '%s\n%s\n' "$$" "$0" > "$AGENT_LOCK_FILE" 2>/dev/null || true
trap 'rm -f "$AGENT_LOCK_FILE" 2>/dev/null || true' EXIT

if [ -x "$AGENTCTL" ]; then
	if "$AGENTCTL" status 2>/dev/null | grep -F 'is running' >/dev/null 2>&1; then
		if ! "$AGENTCTL" stop >/dev/null 2>&1; then
			echo "fail: unable to stop running agent. Abort uninstall."
			exit 1
		fi
		echo "stopped hkexcmdbsync service before uninstall"
	fi
fi

rm -rf "$TARGET_DIR"
# current 可能是相对链接（install.sh 用 ln -sfn "$VERSION"）也可能是绝对路径，
# 直接比整个 readlink 结果会在后一种情况下删不掉，留下指向已删目录的悬空软链。
if [ -L "$CURRENT_LINK" ]; then
	_current_target=$(readlink "$CURRENT_LINK" 2>/dev/null || true)
	if [ -n "$_current_target" ] && [ "$(basename "$_current_target")" = "$VERSION" ]; then
		rm -f "$CURRENT_LINK"
	fi
fi
remove_daemon_aliases

if [ -d "$AGENT_ROOT" ] && [ -z "$(find "$AGENT_ROOT" -mindepth 1 -maxdepth 1 ! -name current 2>/dev/null)" ] && [ ! -L "$CURRENT_LINK" ]; then
	rmdir "$AGENT_ROOT" 2>/dev/null || true
fi

echo "uninstalled $AGENT_NAME packaging from $TARGET_DIR"

# ── 从 omniagentd installParams 中移除 hkexcmdbsync ──
#
# ⚠️ 历史上的事故就出在这一步：对平台管理的 JSON 做**文本替换**，删完数组元素
# 会留下尾随逗号（["cdc","cloudevents"] 删 cloudevents → ["cdc",]），JSON 直接非法，
# 平台解析失败 → 之后所有插件的安装/卸载都报错。
# 所以这里**必须用真正的 JSON 解析**，不能 sed。
INSTALL_PARAMS="/opt/cloudwise/omniagent/meta/installParams"
if [ -f "$INSTALL_PARAMS" ]; then
	if command -v python3 >/dev/null 2>&1; then
		python3 - "$INSTALL_PARAMS" "hkexcmdbsync" <<'PYEOF'
import json, os, re, shutil, sys, time

path, agent = sys.argv[1], sys.argv[2]
try:
    raw = open(path).read()
except OSError as e:
    print("warn: cannot read %s (%s), skip" % (path, e)); sys.exit(0)

# 先把历史遗留的尾随逗号修掉，否则解析不了
fixed = re.sub(r',(\s*[\]\}])', r'\1', raw.strip())
try:
    data = json.loads(fixed)
    if not isinstance(data, dict):
        raise ValueError("top level is not an object")
except ValueError as e:
    print("warn: %s is not valid JSON (%s), leave unchanged" % (path, e)); sys.exit(0)

ids = data.get("agentIds")
if not isinstance(ids, list):
    print("no hkexcmdbsync entry found in %s, left unchanged" % path); sys.exit(0)

new_ids = [i for i in ids if i != agent]
data["agentIds"] = new_ids
out = json.dumps(data, separators=(",", ":"))

# 变更前留备份（平台管理的文件，写坏了平台就认不出已装 agent）
backup = "%s.bak.%d" % (path, int(time.time()))
try:
    shutil.copy2(path, backup)
except OSError:
    backup = "(backup failed)"

try:
    with open(path, "w") as f:
        f.write(out)
    os.chmod(path, 0o644)   # 底座不是 root，600 会让它读不到
except OSError as e:
    print("warn: cannot write %s (%s)" % (path, e)); sys.exit(0)

if len(new_ids) == len(ids):
    print("no hkexcmdbsync entry found in %s, left unchanged" % path)
else:
    print("removed hkexcmdbsync from %s (backup: %s)" % (path, backup))
PYEOF
	else
		# 没有 python3：只做**最小安全操作** —— 去掉尾随逗号，不做删除。
		# 宁可留着一条多余登记（无害），也不要写坏平台文件（有害）。
		echo "warn: python3 not found; skipping installParams cleanup (leaving the entry is harmless, corrupting the file is not)"
	fi
fi