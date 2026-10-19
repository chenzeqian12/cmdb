# CMDB 部署与运维手册

本文档是 `hkex-cmdb-sync` 的统一部署入口，包含 OMP 应用商店交付、Nacos 配置发布、启动验证和配置更新。旧 OmniAgent 插件形态见 [OmniAgent 交付说明](../packaging/README.md)。

## 1. 交付模型

产品和服务名均为 `hkexCmdbSync`，不归属于 `docc`、`douc`、`doop` 或 `gateway`。Maven 在 `package` 阶段生成两个制品：

| 制品 | 用途 |
| --- | --- |
| `hkexCmdbSync-<版本>-<时间戳>-install.tar.gz` | 首次上传，登记产品和服务 |
| `hkexCmdbSync-<版本>-<时间戳>-<提交号>.tar.gz` | 产品登记后的服务升级 |

服务使用 thin jar，交付包中的 `lib/` 是必需运行依赖，不能单独复制主 jar。

## 2. 构建与校验

构建机需要 Java 17、Maven 和 Python 3。

```bash
mvn clean package
OMP_SKIP_BUILD=1 ./omp-package.sh
```

Maven 原始制品位于 `hkex-cmdb-sync-server/target/`，经脚本校验的交付件位于 `dist/omp/`。首次安装上传 `-install.tar.gz` 产品包。

提交前可执行：

```bash
mvn -pl hkex-cmdb-sync-server -am test
bash tests/test-java17-resolution.sh
bash tests/test-omp-package-layout.sh
python3 -m unittest tests/test_omp_product_package.py
```

## 3. OMP 安装

服务依赖：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `jdk17` | `17.0.0` | 服务和配置发布器的 Java 运行时 |
| `nacos` | `2.5.1` | 配置中心及 DOUC Dubbo 服务发现 |

安装步骤：

1. 在 OMP 应用商店上传产品包。
2. 创建服务实例，选择 Java 17 和 Nacos 依赖实例。
   **Nacos 以集群部署时，依赖要选到集群**：脚本会把集群内全部节点的 `ip:service_port`
   拼成逗号分隔的地址串写进 `CW_NACOS_SERVER`（Nacos 客户端的 `serverAddr` 就是这个格式）；
   只选单个实例则退化成单地址。若环境里有多个 Nacos 实例，而依赖既没选集群、也没选具体实例，
   安装会直接失败（`expected one nacos dependency instance, found N`）——这里不做猜测，
   宁可不装也不连错。
3. 安装脚本从 OMP `data_json` 读取 Nacos 地址、用户名、密码密文和命名空间。
4. 脚本在安装目录生成 `conf/nacos.params` 和 `conf/omp.env`，文件权限为 `0600`。
5. 发布完整业务配置后启动服务。

`nacos.params` 和 `omp.env` 只能由现场安装流程生成，不应手工编辑、打入制品或提交到仓库。`CW_NACOS_PASSWORD` 保存 OMP 下发的密文，不填明文。

只检查文件元数据，不打印文件内容：

```bash
stat -c '%a %U %G %n' <安装目录>/conf/nacos.params <安装目录>/conf/omp.env
```

## 4. 准备业务配置

复制 `hkex-cmdb-sync-server/conf/cmdb-app.full.json.example` 到受控临时目录，填写全部六段配置
（`spring`、`server`、`logging`、`hkex`、`servicenow`、`douc`）。Nacos 固定位置为：

| 项目 | 值 |
| --- | --- |
| Group | `hkex-cmdb-sync` |
| Data ID | `hkex-cmdb-sync-config.json` |
| 类型 | JSON |

待发布文件中的 ServiceNow 凭据使用明文输入：

```json
{
  "servicenow": {
    "username": "<username>",
    "password": "<password>"
  }
}
```

这只是字段示意，实际文件不得省略其他配置。包含真实凭据的临时文件不得放入仓库、普通共享目录或日志。

`server.port` 必须与现场 OMP 实际探测的服务端口一致，新装默认值为 `18098`。已有实例须核对安装流程
生成的 `conf/omp.env` 中的 `OMP_SERVICE_PORT` 和实际监听端口，不要仅因升级包默认值不同就改 Nacos。
Nacos 配置以最高优先级注入环境，会盖住启动脚本传的 `-Dserver.port`。
例如现有实例三方均为 `18081` 时保持不变；若要改端口，应同步调整 OMP 服务记录、重新安装生成的
运行参数与 Nacos。不要手改 `omp.env`。

### ServiceNow 专用代理（1.0.11.6 起）

现场需要经 HTTP 代理访问 ServiceNow 时，在**已有的 `servicenow` 对象中**新增以下三个字段：

```json
"proxy-enabled": true,
"proxy-host": "webproxy.hkex",
"proxy-port": 8080
```

这是局部字段示意，不能替换整份配置或整个 `servicenow` 对象。原 `base-url` 仍为 ServiceNow 地址，
不是代理地址；保留原凭据和密文、端口及同步开关。代理字段非敏感、无需重新加密。

- 默认不配或 `proxy-enabled=false` 时保持原有连接方式；包内通用模板默认关闭。
- 开启后 Token POST、用户组成员 GET 都经该代理；HTTPS 使用 CONNECT 隧道。
- 证书校验默认仍然开启；只有显式打开下面的 `allow-insecure-tls` 才会跳过（1.0.11.7 起）。
- `proxy-host` 仅填主机名或 IPv4，不带 `http://`、`:8080`、路径、空白或认证信息；端口为 1–65535。
- 只作用于 ServiceNow 客户端，不设置 JVM 全局代理，不改变 DOUC、Nacos 或 Dubbo 路由。
- 代理不可达则本轮失败，不回退直连。本版本不增加代理认证或热更新能力。

**已有实例升级顺序**：先升级到 1.0.11.6，再向现有 Nacos JSON 添加上述字段并发布，重启服务。
旧版本会拒绝未知字段，不要在旧包仍运行时提前加字段后重启。已有凭据密文不变，无需重跑加密工具。
未加字段的旧配置仍能启动，但需代理的现场仍会因尝试直连而失败。首次验证保持 `douc.dry-run=true`，
避免代理接通后自动同步立即真实写入 DOUC。回滚到旧包前须先移除三个代理字段。

重启后检查：

```bash
grep 'CMDB-STARTUP-CHECK servicenow.proxy' /data/logs/hkexCmdbSync/hkex-cmdb-sync.log
```

应出现 `enabled=true host=webproxy.hkex port=8080`。这只表示代理配置已加载，不能替代实际连通性和 Token 验证。

### ServiceNow 证书不被信任时（1.0.11.7 起）

接上代理后，如果日志出现下面这条，说明**网络已经通了，卡在证书信任**（代理用自己的证书重签流量，
而签发该证书的 CA 不在 JDK 信任库里）：

```text
SSLHandshakeException: PKIX path building failed:
sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path
```

**首选做法**：向 HKEX / 网络侧拿到签发该证书的 CA（一般是企业根 CA），导入服务所用 JDK 的信任库，
证书补齐后把开关关回去：

```bash
cp -p <JDK>/lib/security/cacerts <JDK>/lib/security/cacerts.bak-$(date +%Y%m%d)
<JDK>/bin/keytool -importcert -noprompt -cacerts -storepass changeit -alias hkex-issuing-ca -file <CA.crt>
```

服务所用的 JDK 路径可由启动脚本给出（该命令不涉及任何凭据）：

```bash
cd <安装目录> && bash -c '. ./bin/java17.sh; resolve_java17'
```

**临时绕行**：拿不到证书、需要先把链路跑通时，在 `servicenow` 对象里加一个字段：

```json
"allow-insecure-tls": true
```

- 默认 `false`；通用模板与未配置该字段的环境行为不变。
- 开启后**不再校验对端证书，也不再校验主机名**（两者是独立的两道校验，只关一道仍会失败）。
- 影响范围仅限 ServiceNow 客户端：DOUC 保持直连且始终校验证书（已有单元测试覆盖），
  也不修改 JVM 全局的 `SSLContext` 或默认信任库。
- 开启后启动自检会打 `servicenow.tls-verification=DISABLED` 和一条
  `WARN servicenow.allow-insecure-tls=true`。
- **开启期间 ServiceNow 的集成账号密码与 Token 对链路上任何能应答 `base-url` 的一方都是可见的**，
  属于临时手段，证书补齐后应立即关闭；开启状态不要带入生产验收。

两个开关（`allow-insecure-tls`、`proxy-*`）都是可选平铺字段，缺省可用；类型写错会在配置校验阶段
直接失败并指出字段名，不会静默按默认值继续。

首次发布建议：

- `douc.dry-run=true`
- `hkex.sync.scheduled-enabled=false`
- `hkex.sync.on-startup=false`
- 复核 `douc.top-account-id`、`douc.account-id` 和 `douc.user-id`

## 5. 加密并发布

在已完成 OMP 安装和 Nacos 依赖绑定的服务目录执行：

```bash
./bin/publishCmdbConfig /受控目录/cmdb-config.json
```

发布器会：

1. 校验完整 JSON 和必填项。
2. 分别调用 DOUC 加密 ServiceNow 用户名和密码。
3. 立即解密回读并比对，防止发布不可用密文。
4. 删除明文字段，写入 `username-encrypted` 和 `password-encrypted`。
5. 将完整 JSON 发布到 Nacos。

如需先审核待发布 JSON，可只生成密文文件：

```bash
./bin/publishCmdbConfig --encrypt-only \
  /受控目录/cmdb-config.json \
  /受控目录/cmdb-config.encrypted.json
```

发布成功后安全删除临时明文文件。不要手工修改两个密文字段，也不要删除 DOUC 为在用密文创建的解密登记。

## 6. 启动与验收

```bash
./bin/hkexCmdbSync start
./bin/hkexCmdbSync status
grep CMDB-STARTUP-CHECK /data/logs/hkexCmdbSync/hkex-cmdb-sync.log
```

服务日志落在 OMP 声明的 log_dir（本文档示例为 `/data/logs/hkexCmdbSync`），路径由启动脚本以
`-DcwLogRootPath` 注入，log4j2 优先采用它；Nacos 里的 `logging.file.path` 只在缺少该参数时兜底，
**不要**把它写成 `./logs` 这类相对路径——那会按进程工作目录解析，把日志写进安装目录，
看起来就像「服务在跑但没有日志」。启动脚本自身的 stderr 在同一个目录的 `start.log`：
配置在日志系统初始化之前加载，读配置失败时业务日志一行都不会有，此时改看它。

按以下顺序验收：

1. 自检日志中的 `config-source=nacos group=hkex-cmdb-sync data-id=hkex-cmdb-sync-config.json`
   表示配置确实从 Nacos 读到；显示 `local-only(NOT from Nacos)` 则说明 Group / Data ID 不对。
2. `log-file=` 行给出日志实际落盘位置。若同时出现 `WARN log-dir mismatch`，说明 Nacos 的
   `logging.file.path` 与 OMP 的 log_dir 不一致；日志以 OMP 的 log_dir 为准，把前者改成同一个
   绝对路径或留空。
3. 出现 `WARN xxl.* is still set in the Nacos config` 表示 Nacos 里还留着已废弃的 `xxl` 段
   （1.0.11.5 起每日同步改由进程内定时器负责，不再接入 XXL-Job）。该段已被忽略、不影响启动，
   但应删掉；确认删除后这行 WARN 随即消失。
4. 最后一行 `verdict` 说明本次启动后是否会自动同步、是否会写 DOUC；`write-mode=LIVE` 时前面会有 WARN。
5. 通过服务端口执行 `GET /health`，正常应返回 HTTP 200 和 `{"status":"UP"}`；`GET /metrics` 应返回 Prometheus 文本。1.0.7 起按 OMP 的根路径约定提供这两个只读端点，不使用 `/actuator` 前缀。
6. 调用 `POST /sync/groups/match-check`，确认生产用户匹配率。
7. 调用 `POST /sync/groups?dryRun=true`，复核待建组、未匹配成员和待删除成员。
8. 确认计划后将 `douc.dry-run` 改为 `false`，重新发布完整配置并重启。
9. 仅在业务确认后启用进程内调度。

管理端点只暴露健康和 Prometheus 指标，不开放 env、configprops、heapdump、restart 等端点。健康检查不展示组件或详情；健康贡献器异常时返回 503/DOWN，不伪装为成功。健康通过不代表 SN/DOUC 已完成接口联调或真实同步，端口仍须限制在受控网络内。

日志中不应出现 ServiceNow 明文凭据、完整密文、Nacos 密码或 DOUC token。

## 7. 更新与回滚

配置更新：

1. 以当前模板重新准备一份完整临时 JSON。
2. 运行 `publishCmdbConfig`，不要直接编辑密文字段。
3. 删除临时明文文件并重启服务。
4. 重新执行只读检查和 dry-run。

程序升级时在 OMP 中上传独立服务包。如需回滚，恢复上一个已验证服务包，并确认其与当前 Nacos JSON 字段兼容。

## 8. 失败处理

以下情况会直接阻止启动，服务不会回退到本地明文配置：

- `nacos.params` 缺失或 Nacos 参数不完整。
- Nacos 不可达、认证失败或指定 Data ID 不存在。
- JSON 缺少六个必要分段之一，包含未知字段或类型错误。
- Nacos JSON 含 ServiceNow 明文凭据，或缺少两个密文字段。
- DOUC 不可达，或找不到密文对应的解密登记。

排查时只核对网络、Group/Data ID、字段名、文件权限和错误类型，不要在命令行、工单或日志中打印敏感值。

## 9. 组成员明细日志（1.0.11.8 起）

每次执行成员同步，服务会给**每个组打一行**成员明细，用来回答「这个组为什么是空的 / 谁没匹配上」：

```text
Group members, name=XXX_SUP, groupId=123, source=10, resolved=8, unmatched=2,
existing=7, toAdd=1, toRemove=0, applied=true,
members=[alice@example.com, bob@example.com (UNMATCHED), ...]
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `source` | ServiceNow 侧该组的成员记录数（已清洗去重） |
| `resolved` / `unmatched` | 能在 DOUC 里匹配到用户 / 匹配不到的数量 |
| `existing` | 写入前 DOUC 该组已有成员数 |
| `toAdd` / `toRemove` | 本次相对现状的增、删数量（全量覆盖语义） |
| `applied` | 是否真的写入了；dry-run 或建组失败时为 `false`，并带 `skipped="原因"` |
| `members` | 成员标识（优先邮箱），匹配不到的标 `(UNMATCHED)` |

按组检索：

```bash
grep 'Group members, name=XXX_SUP' /data/logs/hkexCmdbSync/hkex-cmdb-sync.log | tail -1
```

只找所有匹配不上的成员：

```bash
grep -o '[^,[]* (UNMATCHED)' /data/logs/hkexCmdbSync/hkex-cmdb-sync.log | sort | uniq | tail -50
```

体量控制：单组最多列 100 个成员标识、单次同步最多 2000 个；被截断时行尾有
`truncated=true, sourceTotal=N`，不会静默少打。`count=0` 那个日志仍然保留，
含义是「该组本次被全量覆盖成 0 人」。
