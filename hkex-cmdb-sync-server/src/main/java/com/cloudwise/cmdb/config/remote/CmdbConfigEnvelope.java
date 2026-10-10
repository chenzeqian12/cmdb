package com.cloudwise.cmdb.config.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Nacos 中保存的 CMDB 配置密文信封。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CmdbConfigEnvelope(int schemaVersion, String cipherType, String ciphertext) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public static final String SUPPORTED_CIPHER_TYPE = "DOUC_SM2";

    /** 校验固定协议，禁止降级到其他本地解密算法。 */
    public void validate() {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported CMDB config envelope schema version");
        }
        if (!SUPPORTED_CIPHER_TYPE.equals(cipherType)) {
            throw new IllegalStateException("unsupported CMDB config cipher type");
        }
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalStateException("CMDB config ciphertext is empty");
        }
    }
}
