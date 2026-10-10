# CMDB OMP 应用商店部署说明

## 1. 独立产品归属

CMDB 不归属于现有的 `docc`、`douc`、`doop`、`gateway` 或 `docpM` 产品。

上传服务包之前，请先由 OMP 管理员在应用商店中登记独立产品：

| 字段 | 默认值 |
| --- | --- |
| 产品名 | `hkexCmdbSync` |
| 产品版本 | 与服务版本一致，例如 `1.0.5` |

如果未登记，上传服务包会报“归属的产品包在数据库中不存在”。这是平台的产品外键校验，不是服务包损坏。

仓库没有经过验证的 OMP 产品包格式，因此只生成 `kind: service` 的服务包，不伪造产品包。

## 2. 构建服务包

本机需要 Java 17、Maven 和 GNU tar（命令名 `gtar`）。在仓库根目录执行：

```bash
./omp-package.sh
```

默认产物：

```text
dist/omp/hkexCmdbSync-1.0.5.tar.gz
```

如果 OMP 中登记的产品版本与服务版本不同，可在打包时指定：

```bash
OMP_PRODUCT_VERSION='已登记的产品版本' ./omp-package.sh
```

`OMP_PRODUCT_NAME` 默认并应保持为 `hkexCmdbSync`。不要为绕过校验而改成已有产品名。

## 3. 服务依赖

服务包声明两个依赖：

- `jdk17`，版本 `17.0.0`
- `nacos`，版本 `2.5.1`

DOUC 通过 Nacos 注册中心发现，不单独声明 OMP 产品依赖。

安装时，OMP 将所选 Nacos 实例写入 `data_json`。生命周期脚本读取该实例的地址、端口、用户名、密码密文和命名空间，并生成：

- `conf/nacos.params`：Nacos 连接参数，权限 `0600`，密码仍为 `password_enc` 密文。
- `conf/omp.env`：安装目录、日志目录、端口、运行用户和内存，权限 `0600`。

这两个文件都是现场运行时生成，不在服务包中。

## 4. 上传与安装

1. 确认独立产品 `hkexCmdbSync` 和对应版本已登记。
2. 上传 `dist/omp/hkexCmdbSync-<版本>.tar.gz`。
3. 创建服务实例时选择 Nacos 依赖实例。
4. 检查安装目录中的 `conf/nacos.params` 和 `conf/omp.env` 权限均为 `600`，不要打印文件内容。
5. 首次启动前，按《CMDB Nacos DOUC 配置部署指南》发布业务配置密文。
6. 在 OMP 启动 `hkexCmdbSync`，确认进程和端口正常。

`nacos.params` 由安装/升级/回滚脚本依据 OMP 依赖数据自动重建，不应人工维护。`jdk17` 依赖提供的 `JAVA_17_HOME` 同时用于服务启动和 `publishCmdbConfig`。

## 5. 失败处理

| 报错 | 处理 |
| --- | --- |
| 归属的产品包在数据库中不存在 | 先登记独立产品及 YAML 中声明的产品版本，再重新上传 |
| dependencies 校验失败 | 确认 OMP 中存在 `jdk17:17.0.0` 和 `nacos:2.5.1` |
| OMP data_json 未提供 nacos 依赖实例 | 创建实例时选择 Nacos 依赖 |
| Nacos 依赖缺少安装参数 | 检查 Nacos 产品实例是否提供 `username`、`password_enc`、`namespace` 和 `service_port` |
| CMDB 启动失败 | 按 Nacos/DOUC 部署指南检查密文配置与 DOUC 可达性 |

不得把 Nacos 明文密码、业务明文配置、私钥或完整密文写入仓库、服务包、命令行记录或聊天窗口。
