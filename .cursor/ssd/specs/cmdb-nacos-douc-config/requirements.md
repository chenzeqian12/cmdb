# CMDB Nacos 配置字段级加密需求

## 目标

CMDB 同步服务的完整应用配置以可读 JSON 保存在 Nacos。参考 GCP 私钥单独加密方式，仅 ServiceNow 登录用户名和密码经 DOUC 加密。

## 验收要求

1. Nacos 配置使用 `servicenow.username-encrypted` 和 `servicenow.password-encrypted`，不得出现明文 `servicenow.username` 或 `servicenow.password`。
2. 其他应用配置保持可读，不再将整份 JSON 加密为密文信封。
3. 启动时在 Spring 属性绑定前分别解密两个字段，运行时仍以 `servicenow.username/password` 提供给现有客户端。
4. 用户名或密码密文缺失、解密失败或解密结果为空时，应用启动失败。
5. 发布工具读取受控本地 JSON，仅加密其中的 SN 用户名和密码，并以解密回读校验加密结果。
6. 发布前必须完成完整配置校验；非法配置不得调用 DOUC。
7. 日志不得输出明文凭据、完整密文或 Nacos 密码。
8. Nacos 连接参数仍由 OMP 依赖通道提供，不属于本次字段级加密范围。
