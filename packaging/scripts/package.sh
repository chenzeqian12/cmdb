#!/usr/bin/env sh
set -eu

BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
PROJECT_DIR=$(cd "$BASE_DIR/.." && pwd)
# 本工程是 Maven 多模块（hkex-cmdb-sync-common + hkex-cmdb-sync-server），
# packaging 挂在仓库根；jar / 内置 application.yml 都在 server 模块下。
MODULE_DIR="$PROJECT_DIR/hkex-cmdb-sync-server"
TARGET_DIR="$MODULE_DIR/target"
BUILTIN_CONF="$MODULE_DIR/src/main/resources/application.yml"
OUTPUT_DIR_DEFAULT="$PROJECT_DIR/dist"
TEMPLATE_FILE="$BASE_DIR/scripts/install_temp.sh"
STAGING_ROOT="$PROJECT_DIR/target/installer-staging"
DEFAULT_AGENT_NAME="hkexcmdbsync"
# 平台上传文件名约定为 5 段：name-os-arch-version.sh
# OS 字段必须含 arch 段，例如 Linux-x86_64、Linux-aarch64、Linux-any。
# 默认打 x86_64 包；若要 arm/other，调用时显式传 os=Linux-aarch64 即可。
DEFAULT_OS="Linux-x86_64"
DEFAULT_BUNDLE_JRE="false"

AGENT_NAME="$DEFAULT_AGENT_NAME"
OS_NAME="$DEFAULT_OS"
INSTALL_VERSION=""
PACKAGE_VERSION=""
OUTPUT_DIR="$OUTPUT_DIR_DEFAULT"
BUNDLE_JRE="$DEFAULT_BUNDLE_JRE"
BUNDLED_JAVA_HOME=""
# 安装身份（写入 ----PARAMETERS 段的 PARAM_USER/PARAM_GROUP）。
# 默认留空：让 install.sh 依据守护进程 omniagentd 的运行用户自动决定（detect_daemon_user）。
# 这是必须的——平台以 daemon 用户执行 agentctl start/stop，agent 若以别的用户运行，
# daemon 就停不掉它（EPERM），升级会卡在"停 agent"（见 install.sh 的 resolve_install_identity）。
# daemon 用户因机器而异（同一批环境里就有 cloudwise / permissiontest / ap_emp 等多种），不能硬编码。
# 需要固定身份时用 user=<user> group=<group> 显式指定。
PACKAGE_USER=""
PACKAGE_GROUP=""
# 平台下发的非敏感 application.yml 内容（JSON 或 YAML，仿 cdc PARAMETERS 段机制）
JSON_CONF=""
JSON_CONF_CONTENT=""

usage() {
  cat <<'EOF'
usage: sh packaging/scripts/package.sh [name=<agent>] [version=<releaseVersion>] [installVersion=<installVersion>] [os=<osName>] [output=<dir>] [bundleJre=true|false] [javaHome=<path>] [jsonConf=<file>] [user=<user>] [group=<group>]

examples:
  sh packaging/scripts/package.sh
  sh packaging/scripts/package.sh name=hkexcmdbsync version=1.0.0
  sh packaging/scripts/package.sh name=hkexcmdbsync installVersion=1.0.0-SNAPSHOT version=1.0.0 bundleJre=true
  sh packaging/scripts/package.sh jsonConf=packaging/conf/application.yml

jsonConf=<file>:
  把这份配置内容塞进安装包末尾的 ----PARAMETERS 段（PARAM_JSON_CONF），
  install 时写到 <插件目录>/conf/application.yml，覆盖内置默认。
  **JSON 和 YAML 都支持** —— YAML 会按原样多行写入（install_temp.sh 的 read_param
  会一直读到下一个 PARAM_xxx= 为止）。
  不传时默认用内置的 hkex-cmdb-sync-server/src/main/resources/application.yml。

  ⚠️ 传进来的内容会**明文**出现在打好的 .sh 里（`tar -xzf` 或直接 grep 都能取出）。
     因此这里只允许端口、日志、调度等非敏感运行配置，严禁写入 servicenow.*、douc.* 或 Nacos 密码。

user=<user> group=<group>:
  安装身份的运行时用户/组（写入 ----PARAMETERS 段的 PARAM_USER/PARAM_GROUP）。
  默认留空 = 跟随守护进程 omniagentd 的运行用户（推荐，保证平台停得掉 agent）。
  显式指定时该值优先。
EOF
}

fail() {
  echo "fail: $1"
  exit 1
}

build_application_jar() {
  command -v mvn >/dev/null 2>&1 || fail "mvn not found"
  (
    cd "$PROJECT_DIR"
    mvn -DskipTests package
  )
}

sanitize_package_version() {
  version_value="$1"
  version_value=${version_value%-SNAPSHOT}
  version_value=${version_value%-snapshot}
  printf '%s' "$version_value"
}

read_pom_version() {
  awk '
    /<parent>/ { in_parent=1; next }
    /<\/parent>/ { in_parent=0; next }
    in_parent == 0 && /<version>[^<]+<\/version>/ {
      match($0, /<version>[^<]+<\/version>/)
      value=substr($0, RSTART + 9, RLENGTH - 19)
      print value
      exit
    }
  ' "$PROJECT_DIR/pom.xml"
}

read_jar_version() {
  jar_name=$(basename "$APP_JAR")
  jar_version=${jar_name#hkex-cmdb-sync-server-}
  jar_version=${jar_version%.jar}
  printf '%s' "$jar_version"
}

validate_os_name() {
  # 平台 /api/v1/gaia/agent/fileupload 要求 5 段文件名
  # （Cloudwise-<name>-installer-<os>-<arch>-<version>.sh），因此 OS_NAME 必须形如 <os>-<arch>。
  # 允许的 arch 集合按目前 daemon 已知的取；若需要新的，扩 case 即可。
  case "$OS_NAME" in
    *-x86_64|*-x86|*-aarch64|*-arm_64|*-ppc64|*-ppc64le|*-s390x|*-any) ;;
    *) fail "os value must be '<os>-<arch>', got: $OS_NAME (expected e.g. Linux-x86_64, Linux-aarch64, Linux-any)" ;;
  esac
}

resolve_java_home() {
  if [ -n "$BUNDLED_JAVA_HOME" ]; then
    [ -x "$BUNDLED_JAVA_HOME/bin/java" ] || fail "javaHome does not contain bin/java: $BUNDLED_JAVA_HOME"
    return
  fi

  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    BUNDLED_JAVA_HOME="$JAVA_HOME"
    return
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    detected_java_home=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
    if [ -n "$detected_java_home" ] && [ -x "$detected_java_home/bin/java" ]; then
      BUNDLED_JAVA_HOME="$detected_java_home"
      return
    fi
  fi

  java_bin=$(command -v java || true)
  if [ -n "$java_bin" ]; then
    if command -v realpath >/dev/null 2>&1; then
      resolved_java_bin=$(realpath "$java_bin")
    elif command -v readlink >/dev/null 2>&1; then
      resolved_java_bin=$(readlink "$java_bin" 2>/dev/null || true)
      case "$resolved_java_bin" in
        /*) ;;
        '') resolved_java_bin="$java_bin" ;;
        *) resolved_java_bin="$(cd "$(dirname "$java_bin")" && pwd -P)/$resolved_java_bin" ;;
      esac
    else
      resolved_java_bin="$java_bin"
    fi
    candidate_java_home=$(cd "$(dirname "$resolved_java_bin")/.." && pwd -P)
    if [ -x "$candidate_java_home/bin/java" ]; then
      BUNDLED_JAVA_HOME="$candidate_java_home"
      return
    fi
  fi

  fail "unable to locate a Java runtime to bundle; set javaHome=<path> or JAVA_HOME"
}

detect_host_os() {
  host_uname=$(uname -s)
  case "$host_uname" in
    Linux) printf '%s' "Linux" ;;
    Darwin) printf '%s' "macOS" ;;
    AIX) printf '%s' "AIX" ;;
    *) printf '%s' "$host_uname" ;;
  esac
}

validate_bundled_jre_target() {
  host_os=$(detect_host_os)
  if [ "$host_os" != "$OS_NAME" ]; then
    fail "bundleJre=true requires building on the same OS as target package. host=$host_os target=$OS_NAME"
  fi
}

parse_args() {
  for arg in "$@"; do
    case "$arg" in
      name=*) AGENT_NAME=${arg#name=} ;;
      proc=*) ;;
      version=*) PACKAGE_VERSION=${arg#version=} ;;
      installVersion=*) INSTALL_VERSION=${arg#installVersion=} ;;
      os=*) OS_NAME=${arg#os=} ;;
      output=*) OUTPUT_DIR=${arg#output=} ;;
      bundleJre=*) BUNDLE_JRE=${arg#bundleJre=} ;;
      javaHome=*) BUNDLED_JAVA_HOME=${arg#javaHome=} ;;
      jsonConf=*) JSON_CONF=${arg#jsonConf=} ;;
      user=*) PACKAGE_USER=${arg#user=} ;;
      group=*) PACKAGE_GROUP=${arg#group=} ;;
      -h|--help) usage; exit 0 ;;
      *) fail "unsupported argument: $arg" ;;
    esac
  done
}

ensure_prerequisites() {
  [ -f "$TEMPLATE_FILE" ] || fail "template not found: $TEMPLATE_FILE"
  APP_JAR=$(find "$TARGET_DIR" -maxdepth 1 -type f -name 'hkex-cmdb-sync-server-*.jar' ! -name '*sources.jar' ! -name '*javadoc.jar' | head -n 1)
  [ -n "$APP_JAR" ] || fail "jar not found under target. Run mvn clean package first."
  [ -f "$BASE_DIR/bin/agentctl" ] || fail "agentctl not found"
  [ -f "$BASE_DIR/run.sh" ] || fail "run.sh not found"
  [ -f "$BASE_DIR/scripts/install.sh" ] || fail "install.sh not found"
  [ -f "$BASE_DIR/scripts/uninstall.sh" ] || fail "uninstall.sh not found"
  [ -f "$BUILTIN_CONF" ] || fail "内置配置不存在: $BUILTIN_CONF
  它是安装包的默认非敏感运行配置。
  新克隆的仓库里没有这个文件时，从模板复制：
    cp $MODULE_DIR/src/main/resources/application.yml.example $BUILTIN_CONF"
  if [ -n "$JSON_CONF" ]; then
    [ -f "$JSON_CONF" ] || fail "jsonConf file not found: $JSON_CONF"
  fi
}

# ── 把配置源文件转成**单行 JSON** ──
#
# ⚠️ 必须是单行 JSON，不能是多行 YAML。
# 平台的导入解析器是按 GCP 那套格式写的 —— 包内
# ----PARAMETERS 段的 PARAM_JSON_CONF 和 ----DEFAULT-CONFIG-START---- 段
# 都要求「一行 JSON」。塞多行 YAML 进去，平台直接报**语法错误**、包根本导不进来。
# （踩过：1.0.1 就是这么被打回的。）
#
# 但**源文件仍然写成人类友好的 YAML**（带注释、能直接改），打包时转一次即可。
# 落地依旧叫 conf/application.yml —— JSON 是 YAML 的子集，Spring 照读不误。
conf_to_single_line_json() {
  _file=$1
  [ -f "$_file" ] || fail "配置源文件不存在: $_file"

  if command -v ruby >/dev/null 2>&1; then
    ruby -ryaml -rjson -e '
      begin
        obj = YAML.safe_load(File.read(ARGV[0]), aliases: true)
      rescue => e
        STDERR.puts "YAML 解析失败: #{e.message}"
        exit 1
      end
      unless obj.is_a?(Hash)
        STDERR.puts "配置顶层不是对象，无法作为 application.yml"
        exit 1
      end
      forbidden = obj.keys.map(&:to_s) & ["servicenow", "douc"]
      unless forbidden.empty?
        STDERR.puts "安装包配置禁止包含业务凭据段: #{forbidden.sort.join(",")}"
        exit 1
      end
      print JSON.generate(obj)
    ' "$_file" || fail "把 $_file 转成单行 JSON 失败（见上面的 ruby 报错）"
    return 0
  fi

  if command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' >/dev/null 2>&1; then
    python3 -c '
import json, sys, yaml
with open(sys.argv[1]) as f:
    data = yaml.safe_load(f)
if not isinstance(data, dict):
    sys.stderr.write("配置顶层不是对象\n")
    sys.exit(1)
forbidden = sorted(set(data).intersection({"servicenow", "douc"}))
if forbidden:
    sys.stderr.write("安装包配置禁止包含业务凭据段: " + ",".join(forbidden) + "\n")
    sys.exit(1)
print(json.dumps(data, separators=(",", ":")), end="")
' "$_file" || fail "把 $_file 转成单行 JSON 失败（见上面的 python 报错）"
    return 0
  fi

  fail "本机既没有 ruby、也没有带 PyYAML 的 python3，无法把 $_file 转成平台要求的单行 JSON。
  装一个即可：brew install ruby  或  pip3 install pyyaml"
}

# ── 解析 JSON_CONF 内容 ──
# 优先级：--jsonConf= 显式传入 > 内置的 server 模块 application.yml
# 输入是 YAML 还是 JSON 都行，输出统一是**单行 JSON**（平台要求）。
resolve_json_conf() {
  if [ -n "$JSON_CONF" ]; then
    [ -f "$JSON_CONF" ] || fail "jsonConf 文件不存在: $JSON_CONF"
    _conf_src="$JSON_CONF"
  else
    _conf_src="$BUILTIN_CONF"
  fi
  [ -f "$_conf_src" ] || fail "unable to resolve JSON_CONF (pass jsonConf=<file>, or fill $BUILTIN_CONF)"

  JSON_CONF_CONTENT=$(conf_to_single_line_json "$_conf_src")

  [ -n "$JSON_CONF_CONTENT" ] || fail "JSON_CONF 转换后为空: $_conf_src"
  case "$JSON_CONF_CONTENT" in
    \{*\}|\[*\]) ;;
    *) fail "JSON_CONF 转换后不是 JSON（应以 { 或 [ 开头）" ;;
  esac
  # 平台按单行解析，多行必挂 —— 这里再兜一次底
  _line_count=$(printf '%s' "$JSON_CONF_CONTENT" | wc -l | tr -d ' ')
  [ "$_line_count" = "0" ] \
    || fail "JSON_CONF 里含换行符（$_line_count 处），平台会报语法错误。源文件: $_conf_src"
  echo "config: $_conf_src -> 单行 JSON (${#JSON_CONF_CONTENT} bytes)"
}

prepare_versions() {
  if [ -z "$INSTALL_VERSION" ]; then
    INSTALL_VERSION=$(read_jar_version)
  fi

  if [ -z "$INSTALL_VERSION" ]; then
    INSTALL_VERSION=$(read_pom_version)
  fi

  if [ -z "$PACKAGE_VERSION" ]; then
    PACKAGE_VERSION=$(sanitize_package_version "$INSTALL_VERSION")
  fi

  PACKAGE_VERSION=$(sanitize_package_version "$PACKAGE_VERSION")

  [ -n "$AGENT_NAME" ] || fail "agent name cannot be empty"
  [ -n "$OS_NAME" ] || fail "os name cannot be empty"
  [ -n "$INSTALL_VERSION" ] || fail "install version cannot be empty"
  [ -n "$PACKAGE_VERSION" ] || fail "package version cannot be empty"

  case "$PACKAGE_VERSION" in
    *SNAPSHOT*|*snapshot*) fail "package version must not contain SNAPSHOT" ;;
  esac

  validate_os_name
}

prepare_staging() {
  STAGING_DIR="$STAGING_ROOT/$AGENT_NAME"
  rm -rf "$STAGING_DIR"
  mkdir -p \
    "$STAGING_DIR/packaging/bin" \
    "$STAGING_DIR/packaging/conf" \
    "$STAGING_DIR/packaging/jre" \
    "$STAGING_DIR/packaging/scripts" \
    "$STAGING_DIR/target" \
    "$STAGING_DIR/src/main/resources"

  cp "$BASE_DIR/scripts/install.sh" "$STAGING_DIR/packaging/scripts/install.sh"
  cp "$BASE_DIR/scripts/uninstall.sh" "$STAGING_DIR/packaging/scripts/uninstall.sh"
  cp "$BASE_DIR/bin/agentctl" "$STAGING_DIR/packaging/bin/agentctl"
  cp "$MODULE_DIR/bin/publishCmdbConfig" "$STAGING_DIR/packaging/bin/publishCmdbConfig"
  cp "$BASE_DIR/run.sh" "$STAGING_DIR/packaging/run.sh"
  cp "$BUILTIN_CONF" "$STAGING_DIR/src/main/resources/application.yml"
  cp "$APP_JAR" "$STAGING_DIR/target/"

  # 依赖 jar：直接复用同一次构建产出的 docc 形态 tar 包里的 lib/。
  # 这样插件形态和 docc 形态跑的**依赖集合完全一致**，不用再单独维护一份依赖清单
  # （本工程是 thin jar，必须把 lib/ 一起发出去，`-classpath 'lib/*'` 才跑得起来）。
  # 注意：这个 tar 是 **docc 形态**的制品，名字来自根 pom 的 <server-name>（hkexCmdbSync，
  # 驼峰），跟插件名 hkexcmdbsync 是两回事 —— 别顺手改成小写，会找不到文件。
  ASSEMBLY_TAR=$(ls -t "$MODULE_DIR"/target/hkexCmdbSync-*.tar.gz 2>/dev/null | head -n 1)
  [ -n "$ASSEMBLY_TAR" ] || fail "docc 形态 tar 包不存在（$MODULE_DIR/target/hkexCmdbSync-*.tar.gz），无法取依赖 jar"
  rm -rf "$STAGING_DIR/.assembly"
  mkdir -p "$STAGING_DIR/.assembly" "$STAGING_DIR/lib"
  # 包内顶层目录同样是 <baseDirectory>hkexCmdbSync</baseDirectory>（驼峰），别改成小写
  tar -xzf "$ASSEMBLY_TAR" -C "$STAGING_DIR/.assembly" hkexCmdbSync/lib \
    || fail "从 $ASSEMBLY_TAR 里解不出 lib/"
  cp "$STAGING_DIR/.assembly/hkexCmdbSync/lib/"*.jar "$STAGING_DIR/lib/" \
    || fail "从 $ASSEMBLY_TAR 的 lib/ 里没取到 jar"
  rm -rf "$STAGING_DIR/.assembly"
  # tar 包里那个主 jar 带时间戳 + git sha（assembly 的 finalName），统一换成干净命名的那个，
  # 免得每次构建装出来的文件名都不一样。
  rm -f "$STAGING_DIR/lib/hkex-cmdb-sync-server-"*.jar
  cp "$APP_JAR" "$STAGING_DIR/lib/"
  echo "staged lib/: $(ls -1 "$STAGING_DIR/lib" | wc -l | tr -d ' ') jars (deps from $(basename "$ASSEMBLY_TAR"))"

  if [ "$BUNDLE_JRE" = "true" ]; then
    validate_bundled_jre_target
    resolve_java_home
    rm -rf "$STAGING_DIR/packaging/jre"
    cp -R "$BUNDLED_JAVA_HOME" "$STAGING_DIR/packaging/jre"
  fi

  chmod +x \
    "$STAGING_DIR/packaging/scripts/install.sh" \
    "$STAGING_DIR/packaging/scripts/uninstall.sh" \
    "$STAGING_DIR/packaging/bin/agentctl" \
    "$STAGING_DIR/packaging/bin/publishCmdbConfig" \
    "$STAGING_DIR/packaging/run.sh"
}

build_archive() {
  mkdir -p "$OUTPUT_DIR"
  ARCHIVE_FILE="$STAGING_ROOT/${AGENT_NAME}-${INSTALL_VERSION}.tar.gz"
  PACKAGE_FILE="$OUTPUT_DIR/Cloudwise-${AGENT_NAME}-installer-${OS_NAME}-${PACKAGE_VERSION}.sh"
  TEMPLATE_RENDERED="$STAGING_ROOT/${AGENT_NAME}-installer.sh"
  PARAMS_FILE="$STAGING_ROOT/${AGENT_NAME}-params.txt"
  TEMPLATE_WITH_PARAMS="$STAGING_ROOT/${AGENT_NAME}-installer-with-params.sh"

  tar -czf "$ARCHIVE_FILE" -C "$STAGING_DIR" .

  sed \
    -e "s|__PACKAGE_NAME__|Cloudwise-${AGENT_NAME}-installer-${OS_NAME}-${PACKAGE_VERSION}.sh|g" \
    -e "s|__AGENT_NAME__|$AGENT_NAME|g" \
    -e "s|__INSTALL_VERSION__|$INSTALL_VERSION|g" \
    "$TEMPLATE_FILE" > "$TEMPLATE_RENDERED"

  # 把 ----PARAMETERS 段塞到 wrapper（marker 之前），避免污染 payload
  # 如果塞在 payload 之后，install_temp.sh 的 `tail -n +marker_line` 会把
  # ----PARAMETERS 段也截到 tar.gz 末尾，导致 tar -xzf 报 "trailing garbage"。
  {
    # 1) template（去掉末尾的 __HKEX_CMDB_SYNC_ARCHIVE_BELOW__ 行）
    sed '/^__HKEX_CMDB_SYNC_ARCHIVE_BELOW__$/d' "$TEMPLATE_RENDERED"
    # 2) ----DEFAULT-CONFIG-START---- 段（供 DOCC 上传解析，生成配置中心记录+详情页描述）
    printf '\n----DEFAULT-CONFIG-START----\n'
    printf '%s\n' "$JSON_CONF_CONTENT"
    printf '\n----DEFAULT-CONFIG-END----\n'
    # 3) ----PARAMETERS 段（供 install_temp.sh 安装时读取运行时参数）
    printf '\n----PARAMETERS\n'
    printf '%s\n' -- "PARAM_JSON_CONF=$JSON_CONF_CONTENT"
    printf '%s\n' -- "PARAM_USER=$PACKAGE_USER"
    printf '%s\n' -- "PARAM_GROUP=$PACKAGE_GROUP"
    printf '%s\n' -- "----PARAMETERS--"
    # 4) marker（payload 跟在 marker 后面）
    printf '\n%s\n' "__HKEX_CMDB_SYNC_ARCHIVE_BELOW__"
  } > "$TEMPLATE_WITH_PARAMS"

  # 4) 拼接：template (with params) + payload archive
  cat "$TEMPLATE_WITH_PARAMS" "$ARCHIVE_FILE" > "$PACKAGE_FILE"
  rm -f "$TEMPLATE_WITH_PARAMS"
  chmod +x "$PACKAGE_FILE"
}

print_summary() {
  echo "package created: $PACKAGE_FILE"
  echo "install version: $INSTALL_VERSION"
  echo "upload version: $PACKAGE_VERSION"
  echo "target os (name-arch): $OS_NAME"
  echo "PARAM_JSON_CONF bytes: ${#JSON_CONF_CONTENT}"
  if [ "$BUNDLE_JRE" = "true" ]; then
    echo "bundled java home: $BUNDLED_JAVA_HOME"
  else
    echo "bundled java home: disabled"
  fi
}

parse_args "$@"
build_application_jar
ensure_prerequisites
prepare_versions
resolve_json_conf
prepare_staging
build_archive
print_summary
