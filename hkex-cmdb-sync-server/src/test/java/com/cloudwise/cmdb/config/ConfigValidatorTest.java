package com.cloudwise.cmdb.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfigValidator} 单元测试。不加载 Spring 上下文，直接构造属性对象。
 *
 * <p>本工程的 checkstyle 要求方法名匹配 {@code ^[a-z][a-zA-Z0-9]*$}，
 * 所以用 @DisplayName 承载可读描述，方法名保持驼峰。</p>
 *
 * @author hkex-cmdb-sync
 */
class ConfigValidatorTest {

    private ServiceNowProperties serviceNow;

    private DoucProperties douc;

    @BeforeEach
    void setUp() {
        serviceNow = new ServiceNowProperties();
        serviceNow.setBaseUrl("https://example.service-now.com");
        serviceNow.setClientId("client-id");
        serviceNow.setClientSecret("client-secret");
        serviceNow.setUsername("user");
        serviceNow.setPassword("password");

        douc = new DoucProperties();
        douc.setBaseUrl("http://10.0.0.1:18241");
        douc.setAccountId("110");
        douc.setUserId("1");
        douc.setAuthMode("direct");
    }

    @Test
    @DisplayName("配置齐全时校验通过")
    void validateAllPresentPasses() {
        Assertions.assertThatCode(() -> new ConfigValidator(serviceNow, douc).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("direct 模式不需要 cookie 和 appKey")
    void validateDirectModeDoesNotRequireCredentials() {
        douc.setCookie(null);
        douc.setAppKey(null);
        douc.setAppSecret(null);

        Assertions.assertThatCode(() -> new ConfigValidator(serviceNow, douc).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缺少 ServiceNow 凭据时启动失败，并指出具体配置项")
    void validateMissingServiceNowSecretThrowsWithKey() {
        serviceNow.setClientSecret(null);

        Assertions.assertThatThrownBy(() -> new ConfigValidator(serviceNow, douc).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("servicenow.client-secret");
    }

    @Test
    @DisplayName("多项缺失时一次性全部列出，不用反复试错")
    void validateMultipleMissingListsAllInOneFailure() {
        serviceNow.setPassword(null);
        serviceNow.setUsername("");

        Assertions.assertThatThrownBy(() -> new ConfigValidator(serviceNow, douc).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("servicenow.username")
                .hasMessageContaining("servicenow.password");
    }

    @Test
    @DisplayName("cookie 模式必填 douc.cookie")
    void validateCookieModeRequiresCookie() {
        douc.setAuthMode("cookie");

        Assertions.assertThatThrownBy(() -> new ConfigValidator(serviceNow, douc).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("douc.cookie");
    }

    @Test
    @DisplayName("apim 模式必填 appKey 与 appSecret")
    void validateApimModeRequiresAppKeyAndSecret() {
        douc.setAuthMode("apim");
        douc.setAppKey("key-only");

        Assertions.assertThatThrownBy(() -> new ConfigValidator(serviceNow, douc).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("douc.app-secret");
    }

    @Test
    @DisplayName("认证方式拼错时直接报错，而不是当成合法值放行")
    void validateUnknownAuthModeThrows() {
        douc.setAuthMode("direkt");

        Assertions.assertThatThrownBy(() -> new ConfigValidator(serviceNow, douc).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("douc.auth-mode");
    }

    @Test
    @DisplayName("douc.user-id 缺失时启动失败（填错会导致用户列表为空，匹配率静默归零）")
    void validateMissingUserIdThrows() {
        douc.setUserId(null);

        Assertions.assertThatThrownBy(() -> new ConfigValidator(serviceNow, douc).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("douc.user-id");
    }
}
