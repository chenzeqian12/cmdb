package com.cloudwise.cmdb.config.remote;

/** Nacos 配置中仅允许以 DOUC 密文存储的 ServiceNow 登录凭据。 */
public record CmdbEncryptedCredentials(String username, String password) {
}
