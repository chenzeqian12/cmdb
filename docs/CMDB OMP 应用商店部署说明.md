# CMDB OMP 应用商店部署说明

## 1. 产品包与服务包

产品和服务均使用独立名称 `hkexCmdbSync`，不归属于 `docc`、`douc`、`doop` 或 `gateway`。

与 GCP 相同，Maven 在 `package` 阶段先生成服务包，再生成包含服务包的完整产品包：

| 制品 | 用途 |
| --- | --- |
| `hkexCmdbSync-<版本>-<时间戳>-install.tar.gz` | 首次上传，包含 `kind: product` 产品定义并登记产品和服务 |
| `hkexCmdbSync-<版本>-<时间戳>-<提交号>.tar.gz` | 独立服务包，产品登记后升级使用 |

首次安装上传 `-install.tar.gz` 产品包，不需要为绕过服务包归属校验而人工创建其他产品。

## 2. 构建

本机需要 Java 17、Maven；收集脚本还需要 Python 3。在仓库根目录执行：

```bash
mvn clean package
OMP_SKIP_BUILD=1 ./omp-package.sh
```

两个归档由 Maven 直接生成在 `hkex-cmdb-sync-server/target/`，脚本校验后归集到 `dist/omp/`，不重复手工拼包。tar 长文件名采用 GNU 模式。

产品包结构与 GCP 一致：

```text
hkexCmdbSync/
├── hkexCmdbSync.yaml                                产品定义
├── hkexCmdbSync-<版本>-<时间戳>-<提交号>.tar.gz         服务包
└── hkexCmdbSync/
    └── hkexCmdbSync.yaml                            服务定义
```

内嵌服务包保留 CMDB 的 thin jar 和完整 `lib/` 运行依赖，不改为 GCP 的 fat jar；这不影响 OMP 产品安装包格式。

## 3. 服务依赖与安装

服务依赖 `jdk17:17.0.0` 和 `nacos:2.5.1`。DOUC 通过同一 Nacos 注册中心发现，不另行声明产品依赖。

1. 将 `-install.tar.gz` 产品包上传 OMP 应用商店。
2. 创建服务实例，选择 Nacos 和 Java 17 依赖。
3. 安装脚本从 OMP `data_json` 读取所选 Nacos 的地址、端口、用户名、密码密文和命名空间，生成权限为 `0600` 的 `conf/nacos.params`。
4. 同时生成 `conf/omp.env`，记录安装目录、日志目录、端口、运行用户和内存。
5. 使用 `conf/cmdb-app.full.json.example` 的完整七段应用配置填写环境参数，通过 `bin/publishCmdbConfig --encrypt-only <明文JSON> <待发布JSON>` 仅加密 SN 用户名和密码，或通过不带 `--encrypt-only` 的发布模式写入 Nacos。
6. Nacos 配置就绪后启动服务并核验日志及接口。

`nacos.params` 和 `omp.env` 只在现场生成，不在交付包内。`jdk17` 依赖提供的 `JAVA_17_HOME` 同时用于服务及配置工具。首次无 Nacos 配置或缺少 SN 凭据密文时服务拒绝启动，先完成配置再启动，不应回退本地明文。

## 4. Nacos 配置

- Group：`hkex-cmdb-sync`
- Data ID：`hkex-cmdb-sync-config.json`
- 类型：JSON
- 内容：完整可读 JSON，仅 `servicenow.username-encrypted` 和 `servicenow.password-encrypted` 为 DOUC 密文。

原始 JSON 必须包含 `spring`、`server`、`logging`、`xxl`、`hkex`、`servicenow`、`douc` 全部七段，不能使用旧版两段式示例。首次保持 `dry-run=true`、关闭定时及启动同步，核对 `top-account-id`、端口和日志路径。

## 5. 验证与边界

```bash
python3 -m unittest discover -s tests -p 'test_*.py'
OMP_CHECK_ARTIFACTS=1 python3 -m unittest discover -s tests -p 'test_omp_product_package.py'
```

测试检查 Maven 装配顺序、三层产品布局、内外服务定义一致、版本与文件名、内嵌包与独立产物逐字节一致，以及运行时凭据和缓存文件排除。

结构校验通过不等于现场安装验收；仍需在 OMP 执行上传、安装和启动联调。服务当前无内置接口鉴权，必须限制端口访问；`dry-run=true` 不是强制写保护。真实同步全量覆盖成员，未确认匹配率与删除计划前，不调用 `dryRun=false`。
