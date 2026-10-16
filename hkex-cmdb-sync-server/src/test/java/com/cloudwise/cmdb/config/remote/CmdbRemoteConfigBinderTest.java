package com.cloudwise.cmdb.config.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CmdbRemoteConfigBinderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CmdbRemoteConfigBinder binder;

    @BeforeEach
    void setUp() {
        binder = new CmdbRemoteConfigBinder(objectMapper);
    }

    @Test
    @DisplayName("合法配置绑定成 Spring 属性且保留业务默认值")
    void bindValidDocumentReturnsProperties() {
        CmdbRemoteConfigSnapshot snapshot = bind(validJson());
        Assertions.assertThat(snapshot.serviceNow().getBaseUrl()).isEqualTo("https://example.service-now.com");
        Assertions.assertThat(snapshot.douc().getAuthMode()).isEqualTo("apim");
        Assertions.assertThat(snapshot.douc().getUserPageSize()).isEqualTo(500);
        Assertions.assertThat(snapshot.properties())
                .containsEntry("servicenow.client-secret", "client-secret")
                .containsEntry("douc.app-secret", "app-secret")
                .containsEntry("servicenow.username", "user")
                .containsEntry("servicenow.password", "password")
                .doesNotContainKeys("servicenow.username-encrypted", "servicenow.password-encrypted");
    }

    @Test
    @DisplayName("只从 Nacos 文档提取 ServiceNow 用户名和密码密文")
    void extractsOnlyEncryptedCredentials() {
        Assertions.assertThat(binder.encryptedCredentials(validJson()))
                .isEqualTo(new CmdbEncryptedCredentials("encrypted-user", "encrypted-password"));
    }

    @Test
    @DisplayName("引导参数不能由远程密文覆盖")
    void bindUnknownRootFieldFails() {
        String document = validJson().replaceFirst("\\{", "{\"cmdb\":{\"remote-config\":{\"enabled\":false}},");
        Assertions.assertThatThrownBy(() -> bind(document))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cmdb");
    }

    @Test
    @DisplayName("完整应用配置展开为 Spring 属性")
    void bindFullApplicationConfiguration() {
        Assertions.assertThat(bind(validJson()).properties())
                .containsEntry("server.port", 19080)
                .containsEntry("logging.file.path", "./remote-logs")
                .containsEntry("logging.level.com.cloudwise.cmdb", "DEBUG")
                .containsEntry("hkex.sync.scheduled-enabled", false)
                .containsEntry("hkex.heartbeat.file", "./remote-heartbeat");
    }

    @Test
    @DisplayName("Nacos 里残留的 xxl 段被忽略而不是拒绝启动")
    void leftoverXxlJobConfigIsTolerated() throws Exception {
        // 1.0.11.5 移除了 XXL-Job。现场 Nacos 通常还没删这一段，取值也不再合法（端口写成 -1），
        // 但只要它还在就拒绝启动，服务会卡在配置加载阶段且业务日志为空，现场无法定位。
        ObjectNode document = (ObjectNode) objectMapper.readTree(validJson());
        ((ObjectNode) document.withObject("/xxl/job/executor")).put("port", -1);
        ((ObjectNode) document.withObject("/xxl/job")).put("accessToken", "");
        Assertions.assertThat(bind(document.toString()).properties())
                .containsEntry("server.port", 19080)
                .containsEntry("hkex.sync.scheduled-enabled", false);
    }

    @Test
    @DisplayName("删掉 xxl 段后配置仍然完整可用")
    void documentWithoutXxlSectionStillBinds() throws Exception {
        ObjectNode document = (ObjectNode) objectMapper.readTree(validJson());
        document.remove("xxl");
        Assertions.assertThat(bind(document.toString()).properties())
                .doesNotContainKey("xxl.job.accessToken")
                .containsEntry("hkex.sync.scheduled-enabled", false);
    }

    @Test
    @DisplayName("配置绑定异常不泄漏明文值")
    void bindFailureDoesNotExposePlaintextInCause() {
        String document = validJson().replace("\"dry-run\": true", "\"dry-run\": \"PRIVATE_SECRET_SENTINEL\"");
        Assertions.assertThatThrownBy(() -> bind(document))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "org.apache.dubbo", "[org.apache.dubbo]", "[org.apache.dubbo.registry]", "ROOT",
            "org.springframework.web", "org.springframework.web.client.RestTemplate",
            "org_apache_dubbo", "com.alibaba.nacos.client", "com.cloudwise.utils"
    })
    void loggingNamesCannotBypassCredentialProtection(String logger) throws Exception {
        ObjectNode document = (ObjectNode) objectMapper.readTree(validJson());
        ((ObjectNode) document.path("logging")).set("level", objectMapper.createObjectNode().put(logger, "DEBUG"));
        Assertions.assertThatThrownBy(() -> bind(document.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORG.APACHE.DUBBO", "com.ALIBABA.nacos", "com.cloudwise.UTILS"})
    void loggerCaseCannotReenableCredentialBearingLogs(String logger) throws Exception {
        ObjectNode document = (ObjectNode) objectMapper.readTree(validJson());
        ((ObjectNode) document.path("logging")).set("level", objectMapper.createObjectNode().put(logger, "INFO"));
        Assertions.assertThatThrownBy(() -> bind(document.toString()))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/server/port=\"not-a-port\"", "/server/port=70000",
            "/hkex/sync/on-startup=\"not-a-boolean\"", "/hkex/heartbeat/interval-seconds=0",
            "/hkex/sync/cron=\"invalid-cron\"",
            "/server/port=\"\"",
            "/hkex/sync/on-startup=\"\"", "/hkex/sync/startup-delay-seconds=\"\"",
            "/server/shutdown=\"\"", "/spring/jackson/default-property-inclusion=\"\""
    })
    void invalidApplicationValuesFailBeforeEncryption(String replacement) throws Exception {
        ObjectNode document = (ObjectNode) objectMapper.readTree(validJson());
        String[] parts = replacement.split("=", 2);
        int separator = parts[0].lastIndexOf('/');
        ObjectNode parent = (ObjectNode) document.at(parts[0].substring(0, separator));
        parent.set(parts[0].substring(separator + 1), objectMapper.readTree(parts[1]));
        Assertions.assertThatThrownBy(() -> bind(document.toString()))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @Test
    void businessOnlyDocumentCannotAccidentallyEnableScheduledSync() {
        Assertions.assertThatThrownBy(() -> bind(businessJson()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application configuration is incomplete");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scheduled-enabled", "on-startup"})
    void schedulingSwitchesMustBeExplicit(String key) throws Exception {
        ObjectNode document = (ObjectNode) objectMapper.readTree(validJson());
        ((ObjectNode) document.at("/hkex/sync")).remove(key);
        Assertions.assertThatThrownBy(() -> bind(document.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application configuration is incomplete");
    }

    @Test
    @DisplayName("未知业务字段直接拒绝")
    void bindUnknownBusinessFieldFails() {
        String document = validJson().replace("\"client-id\": \"client-id\"",
                "\"client-id\": \"client-id\", \"unexpected\": true");
        Assertions.assertThatThrownBy(() -> bind(document))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected");
    }

    @Test
    @DisplayName("缺少 ServiceNow 密码密文时发布和启动都失败")
    void bindMissingRequiredSecretFails() {
        String document = validJson().replace("\"password-encrypted\": \"encrypted-password\",", "");
        Assertions.assertThatThrownBy(() -> bind(document))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("password-encrypted");
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "password"})
    @DisplayName("Nacos 配置不允许出现 ServiceNow 明文凭据字段")
    void plaintextCredentialFieldsFail(String field) {
        String document = validJson().replace("\"username-encrypted\": \"encrypted-user\",",
                "\"username-encrypted\": \"encrypted-user\", \"" + field + "\": \"leaked\",");
        Assertions.assertThatThrownBy(() -> bind(document))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(field);
    }

    private CmdbRemoteConfigSnapshot bind(String document) {
        return binder.bind(document, "user", "password");
    }

    private String validJson() {
        return businessJson().replaceFirst("\\{", """
                {
                  "spring": {"application": {"name": "remote-cmdb"},
                    "jackson": {"default-property-inclusion": "non_null"}},
                  "server": {"port": 19080, "shutdown": "graceful"},
                  "logging": {"file": {"path": "./remote-logs"},
                    "level": {"root": "WARN", "com.cloudwise.cmdb": "DEBUG"}},
                  "xxl": {"job": {"admin": {"address": ""}, "accessToken": "dummy-token",
                    "executor": {"app": {"name": "remote-cmdb"}, "port": 19599,
                      "log": {"path": "./remote-joblogs", "retention": {"days": 10}}, "ip": ""}}},
                  "hkex": {"sync": {"scheduled-enabled": false, "on-startup": false,
                    "startup-delay-seconds": 10, "cron": "0 0 4 * * ?", "member-sync": true},
                    "heartbeat": {"enabled": true, "file": "./remote-heartbeat", "interval-seconds": 10}},
                """);
    }

    private String businessJson() {
        return """
                {
                  "servicenow": {
                    "base-url": "https://example.service-now.com",
                    "client-id": "client-id",
                    "client-secret": "client-secret",
                    "username-encrypted": "encrypted-user",
                    "password-encrypted": "encrypted-password",
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
