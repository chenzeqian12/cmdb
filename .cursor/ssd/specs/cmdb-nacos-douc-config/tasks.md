# 实施任务

- [x] 1. 确认 GCP `private-key-encrypted` 的字段级加解密模式
- [x] 2. 将 Nacos 存储从整份密文信封改为完整 JSON 文档
- [x] 3. 严格限定 `username-encrypted` 和 `password-encrypted`，拒绝 SN 明文凭据字段
- [x] 4. 启动时逐项解密并注入运行时 `username/password`
- [x] 5. 改造发布工具，仅加密 SN 用户名和密码
- [x] 6. 在 DOUC 调用前校验完整配置，并对加密结果执行回读比对
- [x] 7. 删除旧密文信封类及测试
- [x] 8. 补充明文泄漏拒绝、缺少密文、回读不一致和 Nacos 读写测试
- [x] 9. 通过完整 Maven、Checkstyle、Java 17、OMP 包结构和产品包验证

现场 Nacos/DOUC 实际发布与启动验证由部署阶段执行，本地测试不写入外部系统。
