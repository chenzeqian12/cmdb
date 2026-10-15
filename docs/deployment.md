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
3. 安装脚本从 OMP `data_json` 读取 Nacos 地址、用户名、密码密文和命名空间。
4. 脚本在安装目录生成 `conf/nacos.params` 和 `conf/omp.env`，文件权限为 `0600`。
5. 发布完整业务配置后启动服务。

`nacos.params` 和 `omp.env` 只能由现场安装流程生成，不应手工编辑、打入制品或提交到仓库。`CW_NACOS_PASSWORD` 保存 OMP 下发的密文，不填明文。

只检查文件元数据，不打印文件内容：

```bash
stat -c '%a %U %G %n' <安装目录>/conf/nacos.params <安装目录>/conf/omp.env
```

## 4. 准备业务配置

复制 `hkex-cmdb-sync-server/conf/cmdb-app.full.json.example` 到受控临时目录，填写全部七段配置。Nacos 固定位置为：

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
grep -E '远程配置|启动自检通过|ERROR' \
  logs/start.log logs/hkex-cmdb-sync.log 2>/dev/null
```

按以下顺序验收：

1. 日志显示远程配置加载和启动自检成功。
2. 调用 `POST /sync/groups/match-check`，确认生产用户匹配率。
3. 调用 `POST /sync/groups?dryRun=true`，复核待建组、未匹配成员和待删除成员。
4. 确认计划后将 `douc.dry-run` 改为 `false`，重新发布完整配置并重启。
5. 仅在业务确认后启用进程内调度或 XXL-Job。

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
- JSON 缺少七个必要分段之一，包含未知字段或类型错误。
- Nacos JSON 含 ServiceNow 明文凭据，或缺少两个密文字段。
- DOUC 不可达，或找不到密文对应的解密登记。

排查时只核对网络、Group/Data ID、字段名、文件权限和错误类型，不要在命令行、工单或日志中打印敏感值。
