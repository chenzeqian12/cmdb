# CMDB OMP 应用商店服务包需求

## 目标

为 `hkexCmdbSync` 提供可上传 OMP 应用商店的标准服务包，并复用已实现的 Nacos 配置与 ServiceNow 凭据 DOUC 解密链路。

## 验收要求

1. 服务名固定为 `hkexCmdbSync`。
2. 服务归属使用独立产品 `hkexCmdbSync`，不得复用 `docc`、`douc`、`doop`、`gateway` 等现有产品。
3. 首次安装提供与 GCP 一致的 `kind: product` 产品包，内含产品定义、服务定义和服务包；独立服务包用于产品登记后的升级，不伪造其他产品归属。
4. 服务依赖仅声明 `jdk17` 和 `nacos`，每项均提供版本。
5. Nacos 地址、用户名、密码密文和命名空间从 OMP 依赖通道读取；不得写入安装参数、代码仓库或服务包。
6. 安装、升级和回滚脚本缺少 Nacos 依赖时必须失败，不得回退到本地明文配置。
7. OMP 包使用 GNU tar 生成，包含服务 YAML、生命周期脚本、控制脚本、非敏感配置和运行依赖。
8. 包内不得包含 `nacos.params`、环境明文配置、私钥、AES 密钥或业务配置明文。
