package com.cloudwise.cmdb.config.remote;

import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.config.ServiceNowProperties;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class CmdbRemoteConfigInitializerTest {

    @Test
    @DisplayName("远程业务配置以最高优先级注入 Spring Environment")
    void initializeAddsRemotePropertiesFirst() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("local", Map.of("douc.auth-mode", "cookie")));
        CmdbRemoteConfigSnapshot snapshot = new CmdbRemoteConfigSnapshot(
                new ServiceNowProperties(), new DoucProperties(),
                Map.of("douc.auth-mode", "apim", "servicenow.client-id", "remote-client"));
        CmdbRemoteConfigInitializer initializer = new CmdbRemoteConfigInitializer(settings -> snapshot);

        initializer.initialize(context);

        Assertions.assertThat(context.getEnvironment().getProperty("douc.auth-mode"))
                .isEqualTo("apim");
        Assertions.assertThat(context.getEnvironment().getProperty("servicenow.client-id"))
                .isEqualTo("remote-client");
    }

    @Test
    @DisplayName("显式关闭远程配置时不访问 Nacos 或 DOUC")
    void initializeDisabledSkipsLoader() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test", Map.of("cmdb.remote-config.enabled", false)));
        AtomicBoolean called = new AtomicBoolean();
        CmdbRemoteConfigInitializer initializer = new CmdbRemoteConfigInitializer(settings -> {
            called.set(true);
            throw new AssertionError("loader must not be called");
        });

        initializer.initialize(context);

        Assertions.assertThat(called).isFalse();
    }
}
