package com.cloudwise.cmdb.config.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CmdbRemoteConfigBinderTest {

    private CmdbRemoteConfigBinder binder;

    @BeforeEach
    void setUp() {
        binder = new CmdbRemoteConfigBinder(new ObjectMapper());
    }

    @Test
    @DisplayName("合法配置绑定成 Spring 属性且保留默认值")
    void bindValidDocumentReturnsProperties() {
        CmdbRemoteConfigSnapshot snapshot = binder.bind(validJson());

        Assertions.assertThat(snapshot.serviceNow().getBaseUrl())
                .isEqualTo("https://example.service-now.com");
        Assertions.assertThat(snapshot.douc().getAuthMode()).isEqualTo("apim");
        Assertions.assertThat(snapshot.douc().getUserPageSize()).isEqualTo(500);
        Assertions.assertThat(snapshot.properties())
                .containsEntry("servicenow.client-secret", "client-secret")
                .containsEntry("douc.app-secret", "app-secret");
    }

    @Test
    @DisplayName("未知根字段不能借密文覆盖应用其他配置")
    void bindUnknownRootFieldFails() {
        String document = validJson().replaceFirst("\\{", "{\"server\":{\"port\":1},");

        Assertions.assertThatThrownBy(() -> binder.bind(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server");
    }

    @Test
    @DisplayName("未知业务字段直接拒绝")
    void bindUnknownBusinessFieldFails() {
        String document = validJson().replace(
                "\"client-id\": \"client-id\"",
                "\"client-id\": \"client-id\", \"unexpected\": true");

        Assertions.assertThatThrownBy(() -> binder.bind(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected");
    }

    @Test
    @DisplayName("缺少 ServiceNow 密码时发布和启动都失败")
    void bindMissingRequiredSecretFails() {
        String document = validJson().replace("\"password\": \"password\",", "");

        Assertions.assertThatThrownBy(() -> binder.bind(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("servicenow.password");
    }

    private String validJson() {
        return """
                {
                  "servicenow": {
                    "base-url": "https://example.service-now.com",
                    "client-id": "client-id",
                    "client-secret": "client-secret",
                    "username": "user",
                    "password": "password",
                    "group-query": "group.nameENDSWITH_SUP"
                  },
                  "douc": {
                    "base-url": "https://douc.example.com",
                    "auth-mode": "apim",
                    "app-key": "app-key",
                    "app-secret": "app-secret",
                    "account-id": "110",
                    "top-account-id": "110",
                    "user-id": "1",
                    "dry-run": true
                  }
                }
                """;
    }
}
