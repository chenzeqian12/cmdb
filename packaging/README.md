# OmniAgent 插件形态打包

把 `hkex-cmdb-sync` 打成 **OmniAgent 插件包**，形态与 EventAgent(GCP) / CDC 一致。
`conf/application.yml` 只保存非敏感运行配置；ServiceNow 和 DOUC 业务接入配置保存在 Nacos 密文中。

和仓库里原有的 docc 形态（`bin/hkexCmdbSync` + `conf/*.properties` + XXL-Job）**并存**，互不影响。

---

## 一、打包

```bash
sh packaging/scripts/package.sh name=hkexcmdbsync version=1.0.0
```

产物：

```text
dist/Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.0.sh   # 上传平台 / 直接执行
```

常用参数：

| 参数 | 说明 | 默认 |
|---|---|---|
| `name=` | 插件名 | `hkexcmdbsync` |
| `version=` | 上传版本号，**不能带 SNAPSHOT** | 取 pom 版本 |
| `installVersion=` | 安装目录用的版本号 | 同 version |
| `os=` | 目标平台 | `Linux-x86_64` |
| `jsonConf=` | 平台下发的配置（JSON 或 YAML 都行） | 用内置 `application.yml` |
| `bundleJre=true` | 随包带 JDK，目标机不用预装 | `false` |

> 文件名必须 5 段：`Cloudwise-<插件名>-installer-<OS>-<架构>-<版本>.sh`，
> 平台 `/api/v1/gaia/agent/fileupload` 按 5 段解析，少一段直接拒。

### ⚠️ 插件名必须**全小写**

平台会把插件名**规范化成全小写**再去磁盘上找目录。名字里带大写 = 装上了平台也认不出：

```
平台日志    ： get path [.../agents/hkexcmdbsync/current] error is not exists
平台日志    ： agentid[hkexcmdbsync] get agent info error.
平台带的参数 ： v1.0.3@Linux@hkexcmdbsync@110@x86     ← 全小写
实际安装目录 ： .../agents/hkexCmdbSync/current       ← 驼峰，对不上
```

**只能用 `hkexcmdbsync`。** 现有的 `cdc` / `cloudevents` / `pagemonitor` 都是小写，
所以这个坑只有取驼峰名才会踩到。（1.0.0 ~ 1.0.3 全栽在这。）

### ⚠️ 平台要求配置必须是**单行 JSON**

平台导入包时会解析包内两处，**都要求一行 JSON**：

| 位置 | 内容 |
|---|---|
| `----DEFAULT-CONFIG-START----` 段 | 配置全文，平台拿它建配置中心记录 |
| `----PARAMETERS` 段的 `PARAM_JSON_CONF` | 非敏感运行配置，安装时落到 `conf/application.yml` |

**塞多行 YAML 进去，平台直接报「语法错误」，包根本导不进来。**（1.0.1 就是这么被打回的。）

所以：

- **源文件仍然写成人类友好的 YAML**（`src/main/resources/application.yml`，带注释、好改）
- **打包时自动转成单行 JSON**（`package.sh` 用 ruby 或 python3+PyYAML 转，缺了就报错提示怎么装）
- 落地文件依旧叫 `conf/application.yml` —— JSON 是 YAML 的子集，Spring 照读不误

运维拿着 `conf/application.yml` 想改成 YAML 格式也完全可以，Spring 两种都收。
但 `servicenow.*` 和 `douc.*` 不得写进该文件，必须按部署指南发布为 Nacos 密文。

---

## 二、安装

```bash
# 装到默认目录 /opt/cloudwise/omniagent/agents/hkexcmdbsync
sh dist/Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.0.sh

# 装到别处（调试用）
sh dist/Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.0.sh /tmp/omniagent-agents

# 临时覆盖配置
sh dist/Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.0.sh --jsonConf=/path/to/application.yml
```

装完目录：

```text
/opt/cloudwise/omniagent/agents/hkexcmdbsync/
├── 1.0.0/
│   ├── bin/agentctl         启停脚本
│   ├── lib/                 主 jar + 全部依赖（thin jar，必须整包发）
│   ├── conf/application.yml 平台下发的配置
│   ├── logs/                app.out.log / app.err.log / hkex-cmdb-sync.log
│   ├── run/                 pid
│   ├── heartbeat            心跳（进程自己每 30s 刷一个 10 位秒级时间戳）
│   └── uninstall.sh
└── current -> 1.0.0
```

---

## 二·五、升级（不用卸载）

把新版本的包再跑一遍就行，`install.sh` 认识已有安装：

```bash
sh Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.5.sh
```

它会：**停旧版 → 装新版 → 老版本目录留着（可回滚）→ `current` 切到新版 → 自动启动**。

```
agent/hkexcmdbsync/
├── 1.0.4/          ← 老版本留着
├── 1.0.5/          ← 新版本
└── current -> 1.0.5
```

降级会被拒绝（除非 `--allow-downgrade`）。

### ⚠️ 升级会把本地运行配置整个换掉

`conf/application.yml` 由包内/平台下发的配置**整体覆盖**，现场在服务器上手改的内容**会丢**。

`MERGE_STRATEGY=union` 也救不了 —— 它只做**顶层 key** 的浅合并，
而 `hkex.sync.cron` 这类嵌套值合不了。`douc.*` 已迁移到 Nacos 密文，不在该文件中。

**所以：要改配置去平台上改，别在服务器上改 `conf/application.yml`。** 平台的配置才是权威来源。

### ⚠️ 升级后会立刻跑一次同步

因为 `hkex.sync.on-startup: true`。不想要就设成 `false`。

---

## 三、启停与排查

```bash
C=/opt/cloudwise/omniagent/agents/hkexcmdbsync/current/bin/agentctl
$C start | stop | restart | status | info | log
```

**装完自检**：

```bash
grep "启动自检通过" logs/hkex-cmdb-sync.log        # 核对 accountId / userId / dryRun
grep -E "ERROR|WARN"  logs/hkex-cmdb-sync.log
cat heartbeat                                       # 应是一个 10 位时间戳，且每 30s 变一次
```

---

## 四、改配置

端口、日志、心跳、调度等非敏感配置可修改 `conf/application.yml`。ServiceNow 与 DOUC 配置必须使用
`bin/publishCmdbConfig` 加密发布到 Nacos，再重启服务。完整步骤见
[`docs/CMDB Nacos DOUC 配置部署指南.md`](../docs/CMDB%20Nacos%20DOUC%20配置部署指南.md)。

重点项：

| 配置 | 说明 |
|---|---|
| `douc.user-id` | 位于 Nacos 密文中；填错不报错，只会让匹配率静默归零 |
| `douc.account-id` | 位于 Nacos 密文中；填错会写到别人租户下 |
| `douc.dry-run` | 位于 Nacos 密文中；首次保持 `true`，确认无误再改 `false` |
| `hkex.sync.scheduled-enabled` | 自动同步总开关，`false` = 下面两个触发都不生效 |
| **`hkex.sync.on-startup`** | **服务每次启动后跑一次同步**，默认 `true` |
| `hkex.sync.startup-delay-seconds` | 启动后等几秒再跑，默认 `30`，**别设 0** |
| `hkex.sync.cron` | 每日同步的 cron，默认 `0 0 3 * * ?`（每天凌晨 3 点） |
| `server.port` | 默认 **18081**（插件形态专用） |

### 自动同步的两个触发点

```yaml
hkex:
  sync:
    scheduled-enabled: true      # 总开关
    on-startup: true             # ① 服务每次启动后跑一次
    startup-delay-seconds: 30
    cron: "0 0 3 * * ?"          # ② 每日定时
```

**① 启动触发**：平台重启插件、升级换版本，都会跑一次。

> `startup-delay-seconds` **别设 0**。刚起来的几秒 Web 端口和连接池还没热，立刻发 2 万条
> 记录的全量请求容易失败；延迟也能挡掉"服务崩溃 → 平台反复重启 → 同步风暴"。

**② 定时触发**：默认每天凌晨 3 点。

**只想要其中一个**：

```yaml
on-startup: false              # 只要每日定时
cron: "0 0 3 * * MON"          # 只要每周一
```

> **为什么是 18081 不是 18080**：hkexdev 的 `10.106.3.74` 上 18080 被 nginx 占了，
> 插件装上去会直接起不来。18081 在 74 / 76 上都空着。
> docc 形态（`bin/hkexCmdbSync`）仍然用 18080，两者互不影响。

---

## 五、几个已知约束

1. **thin jar**：主 jar 的 MANIFEST 里没有 `Main-Class`，启动走 `-classpath 'lib/*' <主类>`，
   所以 `lib/` 里的依赖 jar 必须一起发。`package.sh` 会从同一次构建产出的 docc 形态 tar 里取依赖，两边依赖集合天然一致。
2. **不实现 IPC 命名管道**：`agentctl info` 的 `pipe` 恒为 `false`。平台熔断时会走「直接操作进程」那条路。
3. **包内不含业务凭据**：`conf/application.yml` 只允许非敏感运行配置。启动前必须由受控通道准备
   `conf/nacos.params`，并提前向 Nacos 发布 DOUC 密文配置。
4. **docc 形态默认关掉了进程内定时器**：`conf/application.properties` 里 `hkex.sync.scheduled-enabled=false`，
   那个形态由 XXL-Job 调度，两个都开会把同一份同步跑两遍。
