# OmniAgent 插件交付说明

`packaging/` 是为已有 OmniAgent 环境保留的兼容交付通道。新环境默认使用 [OMP 产品包部署](../docs/deployment.md)。

## 打包

```bash
sh packaging/scripts/package.sh name=hkexcmdbsync version=1.0.0
```

产物：

```text
dist/Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.0.sh
```

常用参数：

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `name=` | 插件名，必须全小写 | `hkexcmdbsync` |
| `version=` | 上传版本，不带 `SNAPSHOT` | POM 版本 |
| `installVersion=` | 安装目录版本 | `version` |
| `os=` | 目标平台 | `Linux-x86_64` |
| `jsonConf=` | 平台下发的非敏感运行配置 | 内置配置 |
| `bundleJre=true` | 在包内携带 JDK | `false` |

平台按固定的五段文件名解析产物，插件名也会被转换为小写后查找目录，因此不得改用 `hkexCmdbSync` 驼峰名。

插件包内配置必须是单行 JSON。源配置可继续维护为 YAML，`package.sh` 会在打包时转换。

## 安装与升级

```bash
# 默认安装到 /opt/cloudwise/omniagent/agents/hkexcmdbsync
sh dist/Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.0.sh

# 指定根目录
sh dist/Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.0.sh /tmp/omniagent-agents

# 使用指定非敏感运行配置
sh dist/Cloudwise-hkexcmdbsync-installer-Linux-x86_64-1.0.0.sh \
  --jsonConf=/path/to/application.yml
```

重新执行新版安装包即可升级。脚本停止旧版、保留旧目录、切换 `current` 软链接并启动新版。默认拒绝降级，显式传入 `--allow-downgrade` 才允许。

升级会整体替换 `conf/application.yml`，现场变更应先回写平台配置。启动后是否立即同步由 Nacos 中的 `hkex.sync.on-startup` 控制。

## 配置边界

- `conf/application.yml` 只保存插件启动所需的非敏感本地配置。
- 完整业务配置来自 Nacos，只有 `servicenow.username-encrypted` 和 `servicenow.password-encrypted` 为 DOUC 密文。
- 启动前必须通过受控通道准备 `conf/nacos.params`，并使用 `bin/publishCmdbConfig` 发布业务配置。
- 首次上线保持 `douc.dry-run=true`、`hkex.sync.on-startup=false`。

完整的 Nacos 配置发布和验收步骤见 [部署与运维手册](../docs/deployment.md)。

## 启停与排查

```bash
C=/opt/cloudwise/omniagent/agents/hkexcmdbsync/current/bin/agentctl
$C start
$C stop
$C restart
$C status
$C info
$C log
```

装完后检查：

```bash
grep '启动自检通过' logs/hkex-cmdb-sync.log
grep -E 'ERROR|WARN' logs/hkex-cmdb-sync.log
cat heartbeat
```

`heartbeat` 应是持续更新的 10 位秒级时间戳。服务使用 thin jar，因此 `lib/` 目录必须完整保留。
