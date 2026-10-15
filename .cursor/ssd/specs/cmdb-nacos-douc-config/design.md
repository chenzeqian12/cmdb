# CMDB Nacos 字段级加密设计

## 1. Nacos 数据模型

Nacos 固定使用 Group `hkex-cmdb-sync` 和 Data ID `hkex-cmdb-sync-config.json`。文档为完整可读 JSON，ServiceNow 段使用两个显式密文字段：

```json
{
  "servicenow": {
    "username-encrypted": "<DOUC 密文>",
    "password-encrypted": "<DOUC 密文>"
  }
}
```

`username` 和 `password` 明文字段在 Nacos 文档中被严格拒绝。

## 2. 启动流程

```text
OMP 提供 Nacos 引导参数
  → 读取完整 JSON
  → 严格校验字段范围和两个密文字段
  → 通过 DOUC 分别解密用户名和密码
  → 移除 *-encrypted，注入运行时 username/password
  → 作为最高优先级 PropertySource 注入 Spring
```

解密后的凭据只存在 JVM 内存中，不写回 Nacos 或本地文件。任一阶段失败均阻止应用启动。

## 3. 发布流程

受控输入模板保留明文 `username/password`。发布工具先用占位密文验证整份配置，然后逐项执行 DOUC 加密和立即解密比对，最后删除明文字段并生成两个 `*-encrypted` 字段。`--encrypt-only` 输出权限为 `0600` 的待发布 JSON；默认模式直接写入固定 Nacos 位置。

## 4. 兼容与迁移

新版不再识别 `schemaVersion/cipherType/ciphertext` 整份密文信封。上线前必须使用新发布工具生成或发布新格式 JSON，然后重启服务。
