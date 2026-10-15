# CMDB OMP 应用商店服务包设计

## 1. 产品与服务归属

- 产品名：`hkexCmdbSync`
- 服务名：`hkexCmdbSync`
- 产品版本：与服务版本一致。

Maven `package` 按顺序生成独立服务包与完整产品包。产品包采用 GCP 已有的三层布局，首次上传登记产品及其下服务；独立服务包用于后续升级。

## 2. 依赖

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `jdk17` | `17.0.0` | Java 运行时 |
| `nacos` | `2.5.1` | 配置读取与 DOUC Dubbo 注册中心 |

DOUC 不作为额外 OMP 依赖：应用通过同一 Nacos 注册中心发现 DOUC 服务。

## 3. 安装链路

```text
OMP data_json
  → 定位 hkexCmdbSync 当前实例
  → 按 dependence 定位 Nacos 实例
  → 读取 ip、service_port、username、password_enc、namespace
  → 写 conf/nacos.params（0600，仅密文）
  → 写 conf/omp.env（0600，非敏感运行参数）
  → bin/hkexCmdbSync 启动 JVM
  → JVM 内解密 Nacos 密码
  → 从 Nacos 读取业务配置密文
  → 调用 DOUC 解密
```

## 4. 包结构

产品包 `hkexCmdbSync-<版本>-<时间戳>-install.tar.gz`：

```text
hkexCmdbSync/
├── hkexCmdbSync.yaml                         产品定义 kind: product
├── hkexCmdbSync-<版本>-<时间戳>-<提交号>.tar.gz
└── hkexCmdbSync/
    └── hkexCmdbSync.yaml                     服务定义 kind: service
```

内嵌服务包：

```text
hkexCmdbSync/
├── hkexCmdbSync.yaml
├── service.properties
├── bin/
├── conf/
├── docs/
├── lib/
└── scripts/
```

## 5. 失败与回滚

- 产品未登记：首次上传完整产品包；独立服务包仍要求已登记产品。
- Nacos 依赖缺失或参数不完整：安装失败。
- 应用配置或 DOUC 解密失败：应用启动失败。
- 升级脚本拒绝降级；回滚恢复上一份服务目录并重新生成运行参数文件。
