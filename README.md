# hkex-cmdb-sync

HKEX ServiceNow CMDB 到 Cloudwise DOUC 的用户组同步服务。服务从 ServiceNow 读取 `_SUP` 用户组及成员，在 DOUC 中创建缺失的用户组，并按邮箱或用户别名匹配已有用户。

## 当前范围

| 能力 | 状态 |
| --- | --- |
| 同步 ServiceNow 用户组 | 已实现 |
| 按用户组同步 DOUC 成员 | 已实现 |
| 只读检查用户匹配率 | 已实现 |
| CI 数据同步 | 未实现 |

ServiceNow 用户应先由现有通道同步到 DOUC。本服务不创建用户，只管理用户组和组成员关系。

## 核心流程

1. 分页读取 ServiceNow 的 `_SUP` 组和组员。
2. 读取 DOUC 用户及用户组，建立邮箱和用户别名索引。
3. 计算待创建用户组、未匹配用户和成员增删计划。
4. `dryRun=true` 时只返回计划；显式关闭试运行后才写入 DOUC。

DOUC `/group/distribute` 是全量覆盖接口。传入的必须是该组完整的期望成员集，否则原有成员会被移除。代码已拒绝 `null` 和空列表，但首次上线仍必须先检查 dry-run 计划中的待删除数量。

## 配置模型

业务配置统一保存在 Nacos：

| 项目 | 值 |
| --- | --- |
| Group | `hkex-cmdb-sync` |
| Data ID | `hkex-cmdb-sync-config.json` |
| 格式 | 完整 JSON |

JSON 包含 `spring`、`server`、`logging`、`hkex`、`servicenow`、`douc` 六段。只有以下两个 ServiceNow 登录字段使用 DOUC 密文：

- `servicenow.username-encrypted`
- `servicenow.password-encrypted`

其他字段保持可读。服务拒绝包含 `servicenow.username` 或 `servicenow.password` 明文的 Nacos 配置，也不会回退到本地业务配置。完整的初次部署、配置发布和故障处理见 [部署与运维手册](docs/deployment.md)。

## 开发与验证

需要 Java 17、Maven 和 Python 3。

```bash
# 单元测试与代码规范检查
mvn -pl hkex-cmdb-sync-server -am test

# 完整构建
mvn clean package

# OMP 包结构检查
bash tests/test-java17-resolution.sh
bash tests/test-omp-package-layout.sh
python3 -m unittest tests/test_omp_product_package.py
```

构建产物解压后，在包根目录执行：

```bash
./bin/hkexCmdbSync start -l -f
./bin/hkexCmdbSync status -l
./bin/hkexCmdbSync stop -l
```

`-l` 以解压目录为服务目录，默认使用 `dev` profile。远程业务配置和 `conf/nacos.params` 仍是启动必需项。

## 运维接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/sync/groups?dryRun=&members=` | 执行同步；`dryRun` 缺省时取 `douc.dry-run` |
| `POST` | `/sync/groups/match-check` | 只读检查 SN 成员在 DOUC 的匹配率 |
| `GET` | `/sync/groups/remote` | 只读查看 DOUC 组名与 ID |
| `GET` | `/sync/groups/remote/tree` | 只读查看 DOUC 原始组树 |

服务未内置 HTTP 接口鉴权，部署时必须通过网络策略限制端口访问。

## 调度方式

同步只有进程内调度一种，由 `hkex.sync.scheduled-enabled`、`on-startup` 和 `cron` 控制，
改时间不用动代码、也不用去任何调度后台登记任务。1.0.11.5 起不再接入 XXL-Job：
原来的 `hkexCmdbGroupSyncJob` 处理器已删除，Nacos 配置里残留的 `xxl` 段会被忽略，
启动自检会打一条 WARN 提示删除。

写入开关始终由 `douc.dry-run` 控制。新环境应保持 `true`，完成匹配率和变更计划复核后再切换。

## DOUC 对接要点

- 网关业务头包括 `accountId`、`topAccountId`、`userId` 和 `userName`。
- 业务错误可能仍返回 HTTP 200，客户端同时校验响应体中的业务状态码。
- 生产环境使用 APIM 应用签名，支持 `sm3`、`md5` 和 `sha256`。Cookie 模式只用于受控联调。
- `douc.user-id` 影响用户可见范围；值不正确时可能不报错而直接导致匹配率为零。

## 工程结构

```text
hkex-cmdb-sync/
├── hkex-cmdb-sync-common/       通用响应和异常类
├── hkex-cmdb-sync-server/       服务、配置、启停脚本和打包定义
├── docs/deployment.md           OMP 部署、Nacos 发布和运维手册
├── packaging/                   旧 OmniAgent 插件交付通道
├── tests/                       包结构与脚本测试
└── omp-package.sh               OMP 产品包归集与校验
```

SDD 需求、设计和任务记录位于 `.cursor/ssd/specs/`，用于开发追踪，不作为部署手册。
