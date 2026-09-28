# hkex-cmdb-sync

HKEX ServiceNow CMDB → Cloudwise DOUC/EMP 数据同步服务。

工程分层与命名遵循 [docc](../../docc) 的既有约定（controller / service / integration / config / pojo / constant / exception）。

**DOUC 源码位置**：`../../digital_operation_user_center`。接口行为有疑问时直接读源码，
比对着网关猜路径快得多。本文件里的每个接口结论都标注了对应的源码位置。

## 需求与实现

| 需求 | 实现 | 状态 |
|---|---|---|
| 1. 同步用户组 | SN 的 `_SUP` 组 → `POST /group/add` | 🟢 已实现 |
| 2. 按用户组把用户分类 | SN 组员邮箱 → 解析成 DOUC userId → `POST /group/distribute` | 🟢 已实现 |
| 3. CI 数据同步 | `getTenableAssetList` | ⬜ 未开始 |

**前提**：SN 的用户已经同步到 DOUC 生产环境，因此需求 2 只需做「组员 → 用户」的归类，
不需要在 DOUC 里建用户。用户创建相关调研见文末附录，当前不需要。

### dev 环境演示结果（2026-09-14）

`mvn test -Dtest=DevGroupPopulationIntegrationTest -Dit.enabled=true -Dsurefire.failIfNoSpecifiedTests=false`

| 组 | id | 成员数 |
|---|---|---|
| `AE&I-Application Integration_SUP` | 5 | 24 |
| `Business Development & Sales Support_SUP` | 12 | 24 |
| `CDO-Data Engineering_SUP` | 13 | 24 |
| `CDO_EDP_BAHub_SUP` | 14 | 24 |
| `CDO_EDP_Data_Hub_SUP` | 15 | 24 |
| `CDO_EDP_Metadata_Hub_SUP` | 16 | 21 |

**机制跑通了，但成员用的是 DOUC dev 里已有的用户，不是 SN 用户。** 原因见下节。

### dev 与生产的差异（重要）

**SN 的用户只同步到了生产，dev 里一个都没有。** 所以：

- dev 上匹配率 ≈ 0，**这个数字不代表生产**。dev 的 141 个用户是 CloudWise 自己的账号
  （`zhangsan`、`王五`、`郝玉鹏`…），跟 SN 的 HKEX 人员毫无交集。
- 生产上用户既然已经同步过来了，匹配应该能成立——**但必须实测**，
  用 `POST /sync/groups/match-check`（只读）跑一次才算数。
- 若生产匹配率也低，说明 SN 同步用户时用的关联字段（`userCode`）与 SN 这边回查的
  字段对不上，那就要走 Dubbo 读 `code`，或找当时做用户同步的人确认口径。

dev 的建用户接口同样走不通（明文路径抛裸异常、AES 路径缺运行时而密钥），
所以 dev 上无法补齐 SN 用户，只能验证到「建组 + 按组配人」这一层。

### 关联键：SN 用户 → DOUC 用户怎么对上

需求 2 成立的前提。查过源码后的结论：

| 候选键 | 位置 | 能否用于匹配 |
|---|---|---|
| `userAlias` | `User.userAlias` | ✅ `/user/list` 直接返回 |
| `email` | `User.email` | ✅ 返回，但实测常为 null |
| `userCode` | **`sys_user_info.code`** | ❌ 模型里有（`User.java:161`），但 `UserListResponseObject` 不暴露；`/user/get/{id}` 又返回 AES 加密串，HTTP 侧读不到 |

`userCode` 就是 Dubbo 同步接口（`addOrUpdateUsers`）里那个"第三方系统中的用户id或编码，
用于两边系统的数据比对"，落在 `sys_user_info.code`（见 `User.xml` 的 `getUserCodeAndIds`）。
**它才是设计上的精确关联键，但走 HTTP 拿不到。** 走 Dubbo 可以。

当前实现走 HTTP，因此**同时用 SN 的 `user.email` 与 `user.user_name` 去撞 DOUC 的
`email` / `userAlias`**，逐个试候选键，命中任一即算匹配（`SourceMember.candidateKeys()`）。
两个口径都试，比只用 email 的匹配面宽得多。

### 同步语义

采用 **SN 为准、全量覆盖**（与集成文档「Source system as Source of Truth」一致）：

- 每次同步把组内成员置为该组在 SN 中的当前名单；
- DOUC 里多出来的成员会被移除；
- 结果幂等，重复执行不累积。

### 用户组平铺，不挂父组

107 个组直接建在顶层，与已有顶级组平级，不做层级嵌套。

实现上靠 **`parentId` 传 null**：服务端 `GroupServiceImpl.addGroup` 对
`parentId == null` 的处理是 `level = FIRST_LEVEL` 且跳过父组查找，建出来的组
`groupIsTop=true`。传了不存在的 ID 反而会报 `ADD_ERROR`。

配置留空即平铺：

```properties
douc.parent-group-id=
```

已用 `FlatGroupIntegrationTest` 端到端验证：建出来的组出现在顶层、不在任何组的子孙里。
（dev 里早期建的几个组是改动前建的，挂在 "test Group" 下，属 dev 噪音，不影响生产。）

### 完整链路

```
ServiceNow                          hkex-cmdb-sync                      DOUC
    │                                     │                              │
    │ sys_user_grmember (807 条)          │                              │
    ├────────────────────────────────────>│                              │
    │                                     │ 清洗/去重 → 107 组 / 308 用户 │
    │                                     │                              │
    │                                     │ GET  /group/list ───────────>│ 现有组
    │                                     │<─────────────────────────────│
    │                                     │ 建出缺失的组                  │
    │                                     │ POST /group/add ────────────>│
    │                                     │                              │
    │                                     │ POST /user/list ────────────>│ 全量用户
    │                                     │<─────── 建 email→userId 索引 ─│
    │                                     │                              │
    │                                     │ GET  /group/getUserList/{id}─>│ 现有成员
    │                                     │<─────────────────────────────│
    │                                     │ 算增删差异，全量覆盖           │
    │                                     │ POST /group/distribute ─────>│
```

## 快速开始

```bash
# 1. 准备凭据（已 gitignore，不会入库）
cp hkex-cmdb-sync-server/conf/application-dev.properties.example \
   hkex-cmdb-sync-server/conf/application-dev.properties
# 填入 SN OAuth 凭据与 DOUC 连接信息

# 2. 单元测试（不触网）
mvn test

# 3. dryRun 全链路联调：打印完整同步计划，不写任何数据
mvn test -Dtest=GroupSyncIntegrationTest -Dit.enabled=true \
         -Dsurefire.failIfNoSpecifiedTests=false

# 4. 小样本端到端（会写 DOUC，跑完自动清理）
mvn test -Dtest=SmallSampleSyncIntegrationTest -Dit.enabled=true \
         -Dsurefire.failIfNoSpecifiedTests=false

# 5. 打部署包（见下节「打包与部署」）
mvn clean package
```

> `-Dsurefire.failIfNoSpecifiedTests=false` 是必需的：`hkex-cmdb-sync-common` 模块没有测试，
> 不加这个参数 Maven 会在该模块上报 `No tests matching pattern ... were executed!` 而中断。

## 打包与部署

打包形态与 **docc / doop-server 一致**：`maven-assembly-plugin` 在 `package` 阶段产出 tar.gz，
CI 的 `.build_template` 直接出这个制品，本地 `mvn clean package` 得到同样的东西。

```bash
mvn clean package           # 产物在 hkex-cmdb-sync-server/target/
```

制品名沿用同一套命名约定（`build-helper` 出时间戳，`git-commit-id-plugin` 出提交号）：

```
hkexCmdbSync-1.0.0-20260916183515-89405c43.tar.gz
```

包内结构：

```
hkexCmdbSync/
├── bin/hkexCmdbSync           启停脚本
├── conf/                      配置（改完重启生效，不用重新打包）
│   ├── application.properties       公共配置，与环境无关
│   ├── application-dev.properties   hkexdev 现场环境
│   └── application-prod.properties  生产（模板见 .example）
├── lib/                       主 jar + 57 个 runtime 依赖
├── logs/  pid/                运行时生成
└── README.md
```

部署与启停：

```bash
tar -xzf hkexCmdbSync-1.0.0-*.tar.gz
cd hkexCmdbSync

./bin/hkexCmdbSync start -l -f        # 本地/临时：前台运行，日志直接打到终端
./bin/hkexCmdbSync start -l           # 后台，日志写 logs/start.log
./bin/hkexCmdbSync status -l
./bin/hkexCmdbSync stop -l

SPRING_PROFILE=dev ./bin/hkexCmdbSync start    # 指定环境，默认 prod
```

> `-l`（local）模式以脚本上级目录为服务目录，不需要任何 `CW_*` 部署环境变量，解压即用。
> 不带 `-l` 时按 Cloudwise 部署工具安装的路径运行（`${CW_INSTALL_APP_DIR}` 等占位符由部署工具替换）。

### 配置为什么放在包外的 conf/

这是 docc / doop-server 的既有约定，`bin/hkexCmdbSync` 把 `conf/` 挂到 classpath 上（**排在 `lib/` 之前**），
所以 `conf/` 里的配置优先于 jar 内的同名资源：

| 好处 | 说明 |
|---|---|
| 换环境不用重新打包 | 改 `conf/application-{profile}.properties` 重启即可 |
| 凭据不进 jar | jar 里没有任何环境信息，制品可以放心流转 |
| 与现有部署流程一致 | 运维不用为这个服务学新东西 |

环境由启动脚本的 `SPRING_PROFILE` 选择（`-Dspring.profiles.active=${SPRING_PROFILE}`），
本地 `-l` 模式默认 `dev`，否则默认 `prod`。

**已实测验证（2026-09-16）**：解压 → `./bin/hkexCmdbSync start -l` → 启动自检通过、
Tomcat 起在 18080、`status` 显示 running、`stop` 清理干净无残留进程。

启动自检（`ConfigValidator`）按 `douc.auth-mode` 分支校验必填项，**缺配置直接中断启动**并一次列出所有缺失项，
而不是拖到调用接口才报 401。日志关键字统一为 `【HKEX-CMDB同步】`，方便日志平台检索。

### 换环境要改什么

只改 `conf/application-{profile}.properties`，不用重新打包：

| 配置 | dev（当前值） | 生产 |
|---|---|---|
| `servicenow.*` | HKEX UAT OAuth 账号 | 生产 SN 凭据 |
| `douc.base-url` | `http://10.106.3.75:18241` | 生产 DOUC 地址 |
| `douc.auth-mode` | `direct`（无认证直连） | `apim`（走网关正式鉴权） |
| `douc.account-id` / `top-account-id` | `110` | 生产租户号 |
| `douc.user-id` | `1`（代码默认值） | **必须实测复核**，见下 |
| `douc.dry-run` | `true` | 确认计划无误后再改 `false` |

生产那份照 `conf/application-prod.properties.example` 复制填写，里面已把要确认的四件事列在文件头。

> **`douc.user-id` 默认是 `1`** —— 实测 dev 上 `1` 能看到全部用户，`3` 返回 0 条。
> 它决定 `/user/list` 的可见范围，**填错不会报错**，只会让匹配率静默归零。
> 生产上线前务必用 `POST /sync/groups/match-check`（只读）跑一次确认这个值在生产同样有全局可见权限。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/sync/groups?dryRun=&members=` | 全链路同步。`dryRun` 缺省取配置（默认 true） |
| POST | `/sync/groups/match-check` | 只验证匹配率：不建组、不动成员 |
| GET | `/sync/groups/remote` | 只读：DOUC 组名 → 组 ID |
| GET | `/sync/groups/remote/tree` | 只读：DOUC 原始组树 |

### 定时执行（XXL-Job）

每天 3 点自动跑一次，用的是 Cloudwise 统一的 XXL-Job，与 docc 同一套。

**cron 不在代码里**，在 XXL-Job 管理后台新建任务时填：

| 后台字段 | 值 |
|---|---|
| 执行器 | `xxl.job.executor.app.name` 配的那个，默认 `hkex-cmdb-sync` |
| 运行模式 | `BEAN` |
| JobHandler | `hkexCmdbGroupSyncJob` |
| Cron | `0 0 3 * * ?` |

> 集成文档里写的是「Daily at 05:00」，与 3 点冲突。**以实际需求为准**，
> 要改只改后台 cron，不用动代码。

任务日志直接写到调度中心，能看到每组建了几个、每组增删了几人、哪些成员没匹配上，
不用翻服务端日志。

写不写数据仍由 `douc.dry-run` 决定；为 true 时任务会照常执行并标记成功，
但日志里会显著提示「dryRun 试运行，未写入」——避免"任务跑了却什么都没发生"这种排查成本。

相关配置：

```properties
xxl.job.admin.address=${XXL_JOB_ADMIN_ADDRESS:}
xxl.job.accessToken=${XXL_JOB_ACCESS_TOKEN:}
xxl.job.executor.app.name=hkex-cmdb-sync
xxl.job.executor.port=18599
xxl.job.executor.log.path=./logs/jobhandler
xxl.job.executor.log.retention.days=7
xxl.job.executor.ip=
```

地址留空时执行器仍会启动，但连不上调度中心、任务不会触发，启动日志里会明确告警。

### dryRun 计划长什么样

`dryRun=true` 时结果里每个组都带一份完整计划，上线前据此复核：

```
=== 需求 1：同步用户组 ===
待新建组 104 个，已存在 3 个，失败 0 个

=== 需求 2：按用户组分类用户 ===
源端邮箱匹配上 308 个，未匹配 0 个

组 MS-Markets Systems_SUP    SN成员=12  匹配=11  未匹配=1  DOUC现有=0  待增=11  待删=0
组 PTS-Host_SUP              SN成员=9   匹配=9   未匹配=0  DOUC现有=3  待增=6   待删=0
...

汇总：所有组共待新增成员关系 297 条，待移除 2 条
```

`待删` 非零就说明全量覆盖会移除 DOUC 里的现有成员，需要确认这是预期行为。

## 流水线构建

构建逻辑全在 `docp/architecture` 的共享模板里，工程只声明用哪个 JDK、
以及**多模块下哪个模块产出可执行包**：

```yaml
include:
  - project: 'docp/architecture'
    ref: 'master'
    file: 'cicd/.gitlab-ci-template-for-maven.yml'

variables:
  TOOLS_SETTING_JDK: "jdk17"

build-job:
  extends: .build_template
  parallel:
    matrix:
      - PROJECT_LIST:
          - hkex-cmdb-sync-server
```

`PROJECT_LIST` 的写法与 docc 一致。单模块的 `doop-server` 不需要这一段，
本工程拆成两个模块后要指明制品出自哪个模块。

### OMP 部署

`deploy-omp` 是一个仅手动执行的 GitLab Job，复用 docc 的部署协议：将构建出的
`hkexCmdbSync-*.tar.gz` 上传到 OMP 的
`/data/omp/packages/upgrade_packages/`，再执行：

```bash
bash /data/omp/scripts/deploy upgrade hkexCmdbSync
```

若升级命令失败，Job 会立即调用 `roll_back hkexCmdbSync` 并以失败结束。部署前在该项目的
**受保护 CI/CD Variables** 中配置下列变量；敏感值均不进入仓库：

| 变量 | 说明 |
|---|---|
| `OMP_HOST` | OMP 服务器地址 |
| `OMP_PORT` | SSH 端口；未覆盖时为 `22` |
| `OMP_USER` | SSH 登录用户 |
| `OMP_SSH_PRIVATE_KEY` | 私钥（建议设为 Masked / Protected） |
| `OMP_KNOWN_HOSTS` | 目标机的 known_hosts 条目，用于校验主机身份 |
| `OMP_SERVICE_NAME` | OMP 服务名；默认 `hkexCmdbSync` |

部署 Job 没有使用 `StrictHostKeyChecking=no`，因此必须提供 `OMP_KNOWN_HOSTS`；这避免 CI
在目标地址或主机密钥被篡改时继续上传制品。

**CI 直接产出可部署的 tar.gz** —— 打包逻辑写在 `hkex-cmdb-sync-server/pom.xml` 的
`maven-assembly-plugin` 里，流水线只负责 `mvn package`，不需要额外脚本：

```
mvn package
  ├── git-commit-id-plugin    → git.commit.id.abbrev / git.commit.time
  ├── build-helper-maven-plugin → build.time
  ├── maven-jar-plugin        → thin jar
  └── maven-assembly-plugin   → hkexCmdbSync-<版本>-<时间>-<提交>.tar.gz
```

### 配置与流水线的关系

`conf/application-{dev,prod}.properties` **已 gitignore**，所以：

| 构建来源 | 包内 conf/ | 说明 |
|---|---|---|
| 本地 `mvn package` | 有 dev 配置 | 现场测试包，解压可直接跑 |
| CI `build-job` | 只有 `application.properties` | 生产凭据不应进仓库，部署时由运维在目标机补 `application-prod.properties` |

### 上线前工程侧还缺什么

| 事项 | 谁做 |
|---|---|
| 把工程推到 GitLab（本地已 `git init` 并完成首次提交） | 开发 |
| 构建机要能访问 `repo.yunzhihui.com:18081`（inf-bom）与 `docp.cloud.cloudwise.com`（checkStyle.xml） | 运维 |

### 本地构建与 CI 的差异

只有一条，是**网络限制不是工程问题**，CI 上会自然消失：

| 现象 | 原因 | 处理 |
|---|---|---|
| checkstyle 拉不到云端配置 | 本网络不可达 | 工程里已存了 `checkstyle/checkStyle.xml`，父 POM 的 antrun 拉取失败时会保留本地文件（`ignoreerrors="true"`）。docc 也是这么做的 |

> `git-commit-id-plugin` 之前会因工程不是 git 仓库而中断构建，
> 现在工程已 `git init` 并有提交，这个坑已经消失，**不再需要 `-Dmaven.gitcommitid.skip=true`**。

本地完整构建：

```bash
mvn clean package        # 测试 + 打包，一步到位
mvn test                 # 只要跑测试
```

### 代码规范（踩过的坑）

Cloudwise 的 checkstyle **同时检查 main 与 test 源码**（inf-bom 设了
`includeTestSourceDirectory=true`），规则较严：

- **禁止 tab，必须 4 空格缩进**（`FileTabCharacter` + `Indentation`）
- **禁止静态导入**（`AvoidStaticImport`）——测试里的 `assertThat` / `when` / `verify` 都得写成
  `Assertions.assertThat(...)` / `Mockito.when(...)`
- 单行不超过 150 字符（`LineLength`）
- 变量声明与首次使用相距不超过 3 行，否则声明为 `final`

docc 之所以没撞上第二条，是因为它**一个测试文件都没有**——本工程有 33 个测试，
是这批工程里少见的，规范上要自己扛。

## DOUC 接口清单（全部实测确认）

网关前缀 `/docp/gateway/douc/api/v1`。

| 接口 | 方法 | 用途 | 关键点 |
|---|---|---|---|
| `/group/list` | GET | 组树 | 叶子节点 `children` 为 JSON `null` 不是空数组 |
| `/group/add` | POST | 建组 | **成功时 `data` 为 null**，拿不到新组 ID，须回查组树 |
| `/group/delete/{id}` | POST | 删组 | |
| `/group/getUserList/{id}` | GET | 查组成员 | 需业务头 |
| `/group/distribute` | POST | **配置组成员** | **全量覆盖语义**，详见下节 |
| `/user/list` | POST | 分页查用户 | |
| `/user/add` | POST | 建用户 | 入参见「用户创建」 |
| `/dic/menu` | POST | 字典枚举 | 如 `["USER_TYPE"]` → 内部/外部 |

### 两个必须知道的网关行为

**1. 业务错误一律返回 HTTP 200。** 失败信息在响应体：

```json
{"status":"fail","code":110503,"msg":"同级用户组名称已存在","success":false}
```

不能靠 HTTP 状态码判成败，必须校验 `status=success && code=100000`。

**2. 组相关接口需要三个业务头。** 服务端 `BasicThreadLocalInfoFilter`
（`douc-service/.../configuration/BasicThreadLocalInfoFilter.java`）从中读取并填充 ThreadLocal：

| 头 | 常量 | 说明 |
|---|---|---|
| `accountId` | `Constant.REQUEST_BASE_HEADER_ACCOUNTID_NAME` | 账号 ID |
| `topAccountId` | `Constant.REQUEST_BASE_HEADER_TOP_ACCOUNT_ID_NAME` | 租户 ID |
| `userId` | `Constant.REQUEST_BASE_HEADER_USERID_NAME` | 操作人，用于记日志 |
| `userName` | `Constant.REQUEST_BASE_HEADER_USER_NAME` | 操作人显示名 |

组树与建组不依赖它们，但**组内成员接口缺了会直接 100001 系统错误**。

### `/group/distribute` 是全量覆盖，不是追加

这是最容易踩的坑。服务端 `GroupServiceImpl.addUserListInGroup`
（第 556 行起）的逻辑是：

```java
Set<Long> userIdSet = Sets.newHashSet(groupAndUserId.getUserIdList());
Set<Long> delUserIds = Sets.difference(oldUserIds, userIdSet);   // 先删掉不在列表里的
Set<Long> addUserIds = Sets.difference(userIdSet, oldUserIds);   // 再补上缺的
```

结论：

- `userIdList` 必须传**该组的完整期望成员**，否则原有成员会被删除。
- `userIdList` 传 `null` → 第 564 行 NPE → 100001 系统错误。
- `userIdList` 传空列表 → **清空该组全部成员**。

代码里已对后两种危险入参做前置校验（`DoucClient.setGroupMembers`）。

DTO 里的 `userAliasList` 字段在 HTTP 路径上**未被使用**，按别名同步要走 Dubbo，见下。

## 认证：网关强制，实测确认

**不带 cookie 访问 `/docp/gateway/douc/api/v1/**` 会被网关直接拒掉：**

```
HTTP/1.1 401 Unauthorized
source: gateway
location: /douc/api/v1/sso/source/proxy.html?restapi=/gateway/checkLogin&service=...
```

> **一个曾经判断错的地方**：只读 DOUC 服务源码时，看到 `BasicInfoFilter` 只保护
> `/admin/api/**`、`BasicThreadLocalInfoFilter` 只往 ThreadLocal 塞头不校验，
> 于是推断「DOUC 自己不拦，直连可能不需要认证」。**这个推断是错的**——
> 校验发生在 DOUC 前面的**网关**层，代码不在 `digital_operation_user_center` 仓库里。
> 遇到底层服务看起来"没有鉴权"时，先确认它前面还有什么。

结论：无论走哪条路，**都必须带上网关认可的身份**。

| 方式 | 适用 | 说明 |
|---|---|---|
| SSO 会话 Cookie | 人工联调 | 浏览器会话，会过期，不能给机器用 |
| APIM `appKey`/`appSign` | 生产 | 见下节 |

## 生产接入：APIM 应用鉴权（不用 cookie）

浏览器会话 Cookie 只适合 UAT 联调，**生产不可用**：会话会过期、依赖人工续期、
本质是冒充浏览器。生产走 APIM 应用签名。

### 鉴权方式

调用时带四个头，取值逻辑来自 DOUC 源码
`apim/apimServer/.../service/ApiService.java:357`：

| 头 | 说明 |
|---|---|
| `appKey` | 应用标识 |
| `timestamp` | 毫秒时间戳。**服务端校验有效期（默认 180s），依赖本机时钟** |
| `random` | 一次性随机串，防重放 |
| `appSign` | `hash(appKey + appSecret + timestamp + random)`，**直接拼串、无分隔符** |

`hash` 由服务端配置 `apim.sign.mode` 决定，取值 `sm3`（默认）/ `md5` / `sha256`，
见 `com.cloudwise.apim.utils.EncryptionUtil`。本工程已实现并交叉验证，
见 `ApimSigner` 与 `ApimSignerTest`（期望值由 Python gmssl 独立算出）。

### 切换方式

```properties
douc.auth-mode=apim
douc.app-key=<APIM 控制台创建应用后获取>
douc.app-secret=<同上>
douc.sign-mode=sm3
```

配置了 `apim` 但不给 appKey/appSecret 会**启动即失败**，不会等到第一次请求才 401。

### 上线前需要在 APIM 侧做的事（人工，代码替代不了）

1. 在 APIM 控制台 `https://<host>/#/apim` 创建应用，拿到 `appKey` / `appSecret`；
2. 把本同步用到的 DOUC 接口注册进 APIM 并授权给该应用。内部接口注册时路径须以
   `/gatewayApi` 开头（`ApimConstant.DEFAULT_GATEWAY_API_PATH`），
   tengine 据此把请求转发给 gatewayApi 服务做鉴权，通过后再代转到 DOUC：

   ```
   /group/list          GET
   /group/add           POST
   /group/delete/{id}   POST
   /group/getUserList/{id} GET
   /group/distribute    POST
   /user/list           POST
   ```

3. 确认网络可达与 `apim.sign.mode` 的实际取值（本工程默认按 sm3）。

### 另一条路：Dubbo RPC

DOUC 还提供 `BaseInfoDubboFacade`（底层 `IBaseInfoSyncService`），是专为外部系统
批量同步设计的接口，其中 `synchronizeUserGroupUsersByUserAlias` 能**按别名同步组内成员**，
省掉「邮箱 → userId」这一步。代价是需要 Nacos 注册中心地址与网络可达。
若 APIM 注册流程走不通，这条是备选。

## 实测数据（2026-09-14，HKEX UAT + dev）

| 指标 | 实测值 |
|---|---|
| SN 组员原始记录 | 807 |
| **脏记录（组名或邮箱为空）** | **343（42.5%）** |
| 去重后源组 | 107 |
| 去重后源用户 | 308 |
| DOUC dev 现有组 | 4 |
| SN 用户邮箱在 DOUC dev 中的匹配数 | 0 / 308（**dev 环境无 SN 用户，不具代表性**）|

## 附录：DOUC 建用户调研（当前不需要，留档）

> SN 的用户已经在 DOUC 生产环境里，需求 1/2 都不需要建用户。
> 以下是当初为「万一需要在 DOUC 建用户」做的调研，留档备查。

### 1. 用户创建接口的入参（🟡 未打通）

`/user/add` 的明文 JSON 路径**走不通**。控制器
（`douc-api/.../UserController.java:577`）的真实入参是 AES 加密的 `userInfo`：

```java
if (StringUtils.isNotEmpty(user.getUserInfo())) {
    String userInfo = AesEncryptUtil.desEncrypt(user.getUserInfo(), SecretManager.getAesKey(), SecretManager.getAesIV());
    user = objectMapper.readValue(userInfo, UserDto.class);
}
```

明文路径下协议到的必填字段（用校验报错逐个试出来的）：

| 字段 | 缺失时的报错 |
|---|---|
| `name` | 110070 姓名不能为空 |
| `userAlias` | 110002 用户别名不能为空 |
| `password` / `password2` | 系统错误（`UserController:597` 随机密码模式强制要求） |
| `departmentUserRelations` | 150070 用户关联部门内容为空 |

**再往后的报错是 `UserServiceImpl:1917` 的 catch-all**
（`throw new BaseException(SYSTEM_ERROR, e)`），拿不到根因，需要服务端日志。

AES 密钥没法自行解密——`douc-common-default.properties` 里的
`secret.aes[0].key/iv` 本身是被 SM2/RSA 加密的，运行时才由
`SecretManager.decrypt()` 解开。

**所以不要走 HTTP 复刻加密这条路。** 见下面的建议方案。

前端那套 SM2 传输层加密（网关层）已从
`/docp/portalWeb/js/*.portal.js` 里还原出来，供参考：
用 cookie `aops-cpk` 的公钥做 SM2（模式 1 = C1C3C2）加密 → hex 前拼 `04` → 转字节 → base64。
但即使复刻成功，也只是在用会话 cookie 冒充浏览器，会话一过期就断。

### 2. 匹配率必须在生产环境验证

> **更正（2026-09-14）**：此前记录的「SN 308 个邮箱在 DOUC 匹配 0 个」是**测错环境**导致的，
> 不是数据问题。当时连的是 `hkexdev.cloudwise.com`（dev），而 SN 同步过来的用户在生产环境。
> 该结论已作废，dev 上的匹配率不具参考性。

验证方式：把 `douc.base-url` 指向生产后跑

```bash
# 只读，不建组、不动成员
curl -X POST 'http://localhost:18080/sync/groups/match-check'
```

匹配逻辑（`DoucClient.listAllUsers` + `GroupSyncServiceImpl.buildUserAliasIndex`）
按 `email` 与 `userAlias` 双口径建索引，并对大小写、首尾空格做了归一化。

### 3. 若最终仍需批量建用户，优先走 Dubbo

DOUC 源码里有专门的对外同步接口
（`douc-service/.../service/IBaseInfoSyncService.java`，经 `BaseInfoDubboFacade` 暴露）：

```
addUsers(List<User>) / updateUsers / deleteUsers                 批量用户
addGroups(List<Group>) / updateGroups / deleteGroups             批量用户组
addUserListInGroup / deleteUserListInGroup                       组内成员增删
synchronizeUserGroupUsersByUserAlias(GroupAndUserId)             ★ 按“用户别名”同步组内成员
deleteUserGroupUsersByUserAlias(GroupAndUserId)
```

优势：

- **无会话 cookie，无客户端加密** —— 不需要复刻 SM2/AES。
- **`synchronizeUserGroupUsersByUserAlias` 直接按别名同步成员**，
  SN 的 `user.email` / `user.user_name` 可以直接对接，不用先查出 DOUC 用户 ID。
- **批量接口**，107 个组 / 308 个用户一次推完，比逐个 HTTP 调用高效得多。
- 这正是 docc 自己对接 DOUC 的方式（`docc/gaia-server/.../dubbo/DoucDubbo.java`）。

**需要的信息**：Dubbo 注册中心（Nacos）地址、`douc-dubbo-api` 的 group 与版本、调用授权。

## 待办

- **组名清洗规则待确认**。当前是全部原样建组，零过滤。实测需要业务定夺的样本：

  | 样本 | 问题 |
  |---|---|
  | `[Inactive]TO-System Operations Support_SUP`、`[inactive]S&NO-Network Operations_SUP` | 已停用组，不应建 |
  | `test1_SUP`、`test2_SUP`、`TEST-testing_SUP` | 测试组 |
  | `PTS-Digital Payment Platform_SUP_SUP` | 双后缀 |
  | `TO- Data Centre & Vendor Management_SUP` vs `TO-Data Centre & Vendor Management_SUP` | 仅差一个空格，疑似重复 |
  | `ITIS-EKM-Checker_Sup`、`ITIS-EKM-Operator_Sup` | SN 的 `ENDSWITH` 不区分大小写，混入的小写后缀 |

  确认后在 `GroupSyncServiceImpl.groupMembersByGroupName` 中增加过滤。

- DOUC dev 中留有小样本测试建的 3 个组（id=5/6/7，`AE&I-Application Integration_SUP`
  等），需要时用 `POST /group/delete/{id}` 删除。小样本集成测试自身建的组已自动清理。

- 文档 `HKEX CMDB Integration.md` 第 154-198 行含真实凭据，对外发送前必须脱敏并轮换。

## 工程结构

多模块，目录形态与 docc 的 `gaia-common` + `gaia-server` 一致。

```
hkex-cmdb-sync/
├── pom.xml                              父 POM：inf-bom + 模块声明 + checkstyle + 制品命名
├── checkstyle/checkStyle.xml            云端拉不到时的本地兜底（docc 同款）
├── .gitlab-ci.yml                       走 docp/architecture 的 .build_template
│
├── hkex-cmdb-sync-common/               公共基础包（零第三方依赖）
│   └── src/main/java/com/cloudwise/cmdb/common/
│       ├── api/ApiResponse.java             统一响应信封
│       └── exception/IntegrationException.java  集成异常
│
└── hkex-cmdb-sync-server/               主服务
    ├── bin/hkexCmdbSync                 启停脚本（conf 挂 classpath，含 -l 本地模式）
    ├── conf/                            配置目录，随包发布，不进 jar
    │   ├── application.properties             公共配置（可入库）
    │   ├── application-dev.properties         hkexdev 现场环境（含凭据，已 gitignore）
    │   └── application-prod.properties.example 生产模板
    ├── src/main/assembly/assembly.xml   打包描述：bin/conf/lib → tar.gz
    └── src/main/java/com/cloudwise/cmdb/
        ├── CmdbSyncApplication.java     启动类
        ├── config/
        │   ├── ServiceNowProperties.java
        │   ├── DoucProperties.java
        │   ├── ConfigValidator.java     启动自检：缺配置直接中断启动
        │   ├── HttpClientConfig.java
        │   └── XxlJobConfig.java        执行器（cron 在调度中心配）
        ├── constant/DoucConstants.java  实测得出的路径与业务码
        ├── controller/SyncController.java
        ├── integration/
        │   ├── servicenow/              SN 客户端 + DTO
        │   └── douc/                    DOUC 客户端 + 认证实现
        ├── job/GroupSyncJob.java        每日同步任务
        ├── pojo/dto/                    同步结果与计划
        └── service/
```

### common 里为什么只有两个类

docc 的 `gaia-common` 很厚（注解、配置、工具、db、模板…），是因为它服务多个业务模块。
本工程只有一个使用方，**刻意没有为了对齐结构而硬塞内容**——放进去的只有
`ApiResponse`（通用响应信封）和 `IntegrationException`（通用集成异常），
两者与 HKEX、DOUC、ServiceNow 都无关，别处可以直接复用。

common 保持**零第三方依赖**（只用 Lombok），避免把 web 容器、HTTP 客户端
这类重东西带给每个使用方。后续真有跨模块复用的东西再往里加。

## 设计取舍

**为什么认证要抽象成 `DoucAuthProvider`。** UAT 联调用会话 Cookie，生产用 APIM 应用签名，
两者只是同一接口的两个实现（`CookieDoucAuthProvider` / `ApimDoucAuthProvider`），
由 `douc.auth-mode` 选择。业务层与 `DoucClient` 对此完全无感——
这次从 cookie 切到 apim 没有改动任何业务代码，就是为了这个。

**为什么默认 `dryRun=true`。** 同步目标挂着真实数据，默认值必须最安全。
想真正写入必须显式传 `?dryRun=false` 或改配置。

**为什么组名判重放在业务层而不是只靠 DOUC 报错。** DOUC 的重名拦截（110503）是兜底，
依赖它意味着每次同步都走一遍失败请求。先查组树在本地比对，正常路径下零无效请求。

**为什么 `setGroupMembers` 要拦 null 和空列表。** 服务端是覆盖语义，
这两种入参分别导致 NPE 和清空成员，属于会造成数据损失的操作，不该由调用方凭记忆规避。
