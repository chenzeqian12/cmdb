# CMDB Nacos + DOUC 配置部署指南

## 1. 方案说明

CMDB 同步服务不再从本地文件读取 `servicenow.*` 和 `douc.*` 业务接入配置。

部署后的配置链路如下：

1. 运维在受控目录准备一次性明文 JSON。
2. `publishCmdbConfig` 调用 DOUC 加密。
3. Nacos 只保存 DOUC 返回的密文信封。
4. CMDB 启动时读取密文，调用 DOUC 解密。
5. 解密结果校验通过后，才创建 ServiceNow 和 DOUC 客户端。

Nacos 固定位置：

| 项目 | 值 |
| --- | --- |
| Group | `hkex-cmdb-sync` |
| Data ID | `hkex-cmdb-sync-config.json` |

本地仍保留端口、日志、调度、心跳等运行配置。远程密文只能配置 `servicenow.*` 和 `douc.*`。

## 2. 前置条件

- Nacos 可用，并已启用认证。
- DOUC 已注册到同一 Dubbo 注册中心，CMDB 主机能够访问 DOUC。
- DOUC 的业务公私钥已正确配置。
- DOUC 的 `sys_asymmetric_info` 表可用；发布密文后，不得删除对应的解密登记。
- 运行用户的环境已具备平台 `docp-global.properties`，用于在 JVM 内解开 OMP 下发的 Nacos 密码密文。
- CMDB 主机使用 Java 17。

## 3. OMP 下发 Nacos 启动参数

安装 CMDB 服务时必须选择 `nacos` 依赖实例。OMP 生命周期脚本从 `data_json` 读取该实例的地址、`username`、`password_enc` 和 `namespace`，自动生成安装目录下的 `conf/nacos.params`，文件权限为 `0600`，并归属服务运行用户。不要手工创建、编辑或将此文件打入交付包。

只检查文件存在性和权限，不打印参数内容：

```bash
stat -c '%a %U %G %n' <安装目录>/conf/nacos.params
```

注意：

- `CW_NACOS_PASSWORD` 填平台下发的密文，不填明文。
- 不要使用 OmniAgent `common.ini` 中的 `config_server` 代替 Nacos 地址。
- 不要把 `nacos.params` 提交到仓库或打进交付包。
- `jdk17` OMP 依赖会提供 `JAVA_17_HOME`；CMDB 服务和配置发布器会优先选择并校验 Java 17，不会因系统默认 Java 8 而误启动。

## 4. 准备待发布配置

在受控临时目录创建 `cmdb-config.json`。以下内容仅为结构示例，不得填写真实信息后提交到仓库：

```json
{
  "servicenow": {
    "base-url": "https://servicenow.example.invalid",
    "client-id": "<client-id>",
    "client-secret": "<client-secret>",
    "username": "<username>",
    "password": "<password>"
  },
  "douc": {
    "base-url": "https://douc.example.invalid",
    "auth-mode": "apim",
    "app-key": "<app-key>",
    "app-secret": "<app-secret>",
    "account-id": "<account-id>",
    "top-account-id": "<top-account-id>",
    "user-id": "<user-id>",
    "dry-run": true
  }
}
```

发布工具会拒绝未知根字段、未知配置字段和缺少必填项的配置。

## 5. 加密并发布到 Nacos

首次安装 CMDB 服务并完成 OMP Nacos 依赖绑定后，在安装目录执行：

```bash
./bin/publishCmdbConfig /受控临时目录/cmdb-config.json
```

工具会：

1. 校验明文 JSON。
2. 调用 DOUC 加密，且不重试加密请求。
3. 向 Nacos 的 `hkex-cmdb-sync` / `hkex-cmdb-sync-config.json` 发布密文信封。
4. 只输出密文长度和摘要前缀，不输出明文或完整密文。

发布成功后，应立即安全删除临时明文文件。不要删除 DOUC 为该密文生成的解密登记。

## 6. 启动与验证

```bash
./bin/hkexCmdbSync start
./bin/hkexCmdbSync status
```

检查日志：

```bash
grep -E "CMDB integration configuration loaded from Nacos through DOUC|启动自检通过" \
  logs/start.log logs/hkex-cmdb-sync.log 2>/dev/null
```

正常情况下，应看到远程配置加载成功和启动自检通过。日志中不应出现明文凭据、完整密文、Nacos 密码或 DOUC token。

## 7. 更新配置

1. 重新准备一份完整的临时明文 JSON。
2. 再次运行 `publishCmdbConfig`。
3. 发布成功后删除临时明文文件。
4. 重启 CMDB 服务。

每次发布都会生成新的 DOUC 密文和对应解密登记。不要手工修改 Nacos 中的密文，也不要清理仍在使用的登记记录。

## 8. 失败处理

以下任一情况都会直接阻止服务启动，不会回退到本地明文配置：

- 缺少 Nacos 启动参数。
- Nacos 不可达、认证失败或配置不存在。
- 密文信封格式错误。
- DOUC 不可达或找不到密文登记。
- 解密结果包含未知字段或缺少必填项。

排查时只检查连接状态、Group/Data ID、字段名称和日志错误类型，不要打印任何密码、明文配置或完整密文。
