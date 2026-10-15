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

        initializer.postProcessEnvironment(context.getEnvironment(), new org.springframework.boot.SpringApplication());

        Assertions.assertThat(context.getEnvironment().getProperty("douc.auth-mode"))
                .isEqualTo("apim");
        Assertions.assertThat(context.getEnvironment().getProperty("servicenow.client-id"))
                .isEqualTo("remote-client");
    }

    @Test
    @DisplayName("配置在日志初始化前的 Environment 阶段加载")
    void loadsBeforeLoggingAndPropertyBinding() {
        org.springframework.core.env.StandardEnvironment environment =
                new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("local", Map.of(
                "server.port", 18080, "logging.level.com.cloudwise.cmdb", "INFO")));
        CmdbRemoteConfigSnapshot snapshot = new CmdbRemoteConfigSnapshot(
                new ServiceNowProperties(), new DoucProperties(), Map.of(
                "server.port", 19080, "logging.level.com.cloudwise.cmdb", "DEBUG"));
        Object loader = new CmdbRemoteConfigInitializer(settings -> snapshot);

        Assertions.assertThat(loader)
                .isInstanceOf(org.springframework.boot.env.EnvironmentPostProcessor.class);
        ((org.springframework.boot.env.EnvironmentPostProcessor) loader).postProcessEnvironment(
                environment, new org.springframework.boot.SpringApplication());

        Assertions.assertThat(environment.getProperty("server.port")).isEqualTo("19080");
        Assertions.assertThat(environment.getProperty("logging.level.com.cloudwise.cmdb"))
                .isEqualTo("DEBUG");
    }

    @Test
    void remoteBootstrapDecryptsOnlyCredentialsAndClosesClient() {
        CmdbRemoteConfigSnapshot snapshot = new CmdbRemoteConfigSnapshot(
                new ServiceNowProperties(), new DoucProperties(), Map.of("server.port", 19080));
        String previousRegistry = System.getProperty("dubbo.registry.address");
        DoucCryptoClient client = org.mockito.Mockito.mock(DoucCryptoClient.class);
        org.mockito.Mockito.when(client.decrypt("encrypted-user")).thenReturn("sn-user");
        org.mockito.Mockito.when(client.decrypt("encrypted-password")).thenReturn("sn-password");
        try (var globals = org.mockito.Mockito.mockStatic(com.cloudwise.utils.GlobalPropertiesUtils.class);
                var crypto = org.mockito.Mockito.mockStatic(DoucCryptoClient.class);
                var repositories = org.mockito.Mockito.mockConstruction(NacosCmdbConfigRepository.class,
                        (repository, context) -> org.mockito.Mockito.when(repository.loadDocument())
                                .thenReturn("dummy-document"));
                var binders = org.mockito.Mockito.mockConstruction(CmdbRemoteConfigBinder.class,
                        (binder, context) -> {
                            org.mockito.Mockito.when(binder.encryptedCredentials("dummy-document"))
                                    .thenReturn(new CmdbEncryptedCredentials(
                                            "encrypted-user", "encrypted-password"));
                            org.mockito.Mockito.when(binder.bind(
                                            "dummy-document", "sn-user", "sn-password"))
                                    .thenReturn(snapshot);
                        })) {
            globals.when(com.cloudwise.utils.GlobalPropertiesUtils::loadNacosProperties)
                    .thenAnswer(invocation -> {
                        System.setProperty("dubbo.registry.address", "dummy-registry");
                        return null;
                    });
            crypto.when(() -> DoucCryptoClient.connect("dummy-registry", "hkex-cmdb-sync-config-loader"))
                    .thenReturn(client);
            var environment = new org.springframework.core.env.StandardEnvironment();
            new CmdbRemoteConfigInitializer().postProcessEnvironment(
                    environment, new org.springframework.boot.SpringApplication());
            new CmdbRemoteConfigInitializer(settings -> {
                throw new AssertionError("must not load twice");
            }).postProcessEnvironment(environment, new org.springframework.boot.SpringApplication());
            Assertions.assertThat(environment.getProperty("server.port")).isEqualTo("19080");
            org.mockito.Mockito.verify(client).decrypt("encrypted-user");
            org.mockito.Mockito.verify(client).decrypt("encrypted-password");
            org.mockito.Mockito.verify(client).close();
        } finally {
            if (previousRegistry == null) {
                System.clearProperty("dubbo.registry.address");
            } else {
                System.setProperty("dubbo.registry.address", previousRegistry);
            }
        }
    }

    @Test
    void remoteBootstrapMissingRegistryFailsBeforeConnecting() {
        String previous = System.getProperty("dubbo.registry.address");
        System.clearProperty("dubbo.registry.address");
        try (var globals = org.mockito.Mockito.mockStatic(com.cloudwise.utils.GlobalPropertiesUtils.class);
                var crypto = org.mockito.Mockito.mockStatic(DoucCryptoClient.class);
                var repositories = org.mockito.Mockito.mockConstruction(NacosCmdbConfigRepository.class,
                        (repository, context) -> org.mockito.Mockito.when(repository.loadDocument())
                                .thenReturn("dummy-document"));
                var binders = org.mockito.Mockito.mockConstruction(CmdbRemoteConfigBinder.class,
                        (binder, context) -> org.mockito.Mockito.when(
                                        binder.encryptedCredentials("dummy-document"))
                                .thenReturn(new CmdbEncryptedCredentials(
                                        "encrypted-user", "encrypted-password")))) {
            Assertions.assertThatThrownBy(() -> new CmdbRemoteConfigInitializer().postProcessEnvironment(
                    new org.springframework.core.env.StandardEnvironment(), new org.springframework.boot.SpringApplication()))
                    .hasMessageContaining("dubbo.registry.address");
        } finally {
            if (previous != null) {
                System.setProperty("dubbo.registry.address", previous);
            }
        }
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

        initializer.postProcessEnvironment(context.getEnvironment(), new org.springframework.boot.SpringApplication());

        Assertions.assertThat(called).isFalse();
    }
}
