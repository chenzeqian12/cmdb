package com.cloudwise.cmdb.integration.servicenow.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * ServiceNow OAuth 换 token 的响应。
 *
 * @author hkex-cmdb-sync
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnTokenResponse {

    /** Bearer Token 原文。 */
    @JsonProperty("access_token")
    private String accessToken;

    /** 固定为 Bearer。 */
    @JsonProperty("token_type")
    private String tokenType;

    /** 有效期，单位秒。实测 UAT 为 1799。 */
    @JsonProperty("expires_in")
    private long expiresIn;

    /** 刷新令牌，本集成暂不使用。 */
    @JsonProperty("refresh_token")
    private String refreshToken;

    /** 错误码，换取失败时返回。 */
    private String error;

    /** 错误描述，换取失败时返回。 */
    @JsonProperty("error_description")
    private String errorDescription;
}
