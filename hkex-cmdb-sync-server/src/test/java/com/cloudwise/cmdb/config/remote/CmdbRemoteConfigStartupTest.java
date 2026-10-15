package com.cloudwise.cmdb.config.remote;

import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.config.ServiceNowProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class CmdbRemoteConfigStartupTest {

    @TempDir
    Path directory;

    @Test
    void springFactoriesLoadsRemoteConfigBeforeLoggingInitialization() throws Exception {
        CmdbRemoteConfigSnapshot snapshot = new CmdbRemoteConfigSnapshot(
                new ServiceNowProperties(), new DoucProperties(), Map.of(
                "server.port", 19080,
                "logging.level.com.cloudwise.cmdb", "DEBUG",
                "logging.file.path", directory.toString(),
                "hkex.sync.scheduled-enabled", false,
                "hkex.heartbeat.file", directory.resolve("heartbeat").toString(),
                "xxl.job.executor.port", 19599));
        CmdbRemoteConfigInitializer actualLoader = new CmdbRemoteConfigInitializer(settings -> snapshot);
        try (MockedConstruction<CmdbRemoteConfigInitializer> loaders = Mockito.mockConstruction(
                CmdbRemoteConfigInitializer.class, (loader, context) -> {
                    Mockito.when(loader.getOrder()).thenReturn(
                            org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER + 1);
                    Mockito.doAnswer(invocation -> {
                        actualLoader.postProcessEnvironment(
                                invocation.getArgument(0), invocation.getArgument(1));
                        return null;
                    }).when(loader).postProcessEnvironment(Mockito.any(), Mockito.any());
                })) {
            SpringApplication application = new SpringApplication(EmptyConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setRegisterShutdownHook(false);
            try (ConfigurableApplicationContext context = application.run(
                    "--cmdb.remote-config.enabled=true", "--spring.profiles.active=remote-test")) {
                Assertions.assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("19080");
                Assertions.assertThat(context.getEnvironment().getProperty("xxl.job.executor.port"))
                        .isEqualTo("19599");
                Assertions.assertThat(LogManager.getLogger("com.cloudwise.cmdb").getLevel())
                        .isEqualTo(Level.DEBUG);
                Assertions.assertThat(Files.isRegularFile(directory.resolve("hkex-cmdb-sync.log"))).isTrue();
                Assertions.assertThat(loaders.constructed()).hasSize(1);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
