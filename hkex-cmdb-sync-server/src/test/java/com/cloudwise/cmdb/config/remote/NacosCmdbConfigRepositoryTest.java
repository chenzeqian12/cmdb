package com.cloudwise.cmdb.config.remote;

import com.alibaba.nacos.api.config.ConfigService;
import java.util.Properties;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NacosCmdbConfigRepositoryTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("cwNacosServer");
        System.clearProperty("cwNacosNamespace");
        System.clearProperty("cwNacosUserName");
        System.clearProperty("cwNacosPassword");
    }

    @Test
    @DisplayName("从固定位置读取完整 JSON 配置并关闭客户端")
    void loadDocumentPasses() throws Exception {
        System.setProperty("cwNacosServer", "127.0.0.1:8848");
        ConfigService service = Mockito.mock(ConfigService.class);
        String document = "{\"servicenow\":{\"username-encrypted\":\"abc\"}}";
        Mockito.when(service.getConfig("hkex-cmdb-sync-config.json", "hkex-cmdb-sync", 3000L))
                .thenReturn(document);
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(
                CmdbRemoteConfigSettings.defaults(), properties -> service);

        Assertions.assertThat(repository.loadDocument()).isEqualTo(document);
        Mockito.verify(service).shutDown();
    }

    @Test
    @DisplayName("发布时原样写入 JSON 配置")
    void publishDocumentPasses() throws Exception {
        System.setProperty("cwNacosServer", "127.0.0.1:8848");
        ConfigService service = Mockito.mock(ConfigService.class);
        Mockito.when(service.publishConfig(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(
                CmdbRemoteConfigSettings.defaults(), properties -> service);

        repository.publishDocument("{\"servicenow\":{}}");

        Mockito.verify(service).publishConfig("hkex-cmdb-sync-config.json", "hkex-cmdb-sync",
                "{\"servicenow\":{}}", "json");
        Mockito.verify(service).shutDown();
    }

    @Test
    void providerFailureDoesNotExposeCredentialInCause() throws Exception {
        System.setProperty("cwNacosServer", "127.0.0.1:8848");
        ConfigService service = Mockito.mock(ConfigService.class);
        Mockito.when(service.getConfig(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
                .thenThrow(new IllegalStateException("PRIVATE_SECRET_SENTINEL"));
        Mockito.when(service.publishConfig(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new IllegalStateException("PRIVATE_SECRET_SENTINEL"));
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(
                CmdbRemoteConfigSettings.defaults(), properties -> service);

        Assertions.assertThatThrownBy(repository::loadDocument).hasNoCause()
                .hasMessageNotContaining("PRIVATE_SECRET_SENTINEL");
        Assertions.assertThatThrownBy(() -> repository.publishDocument("{\"value\":\"safe\"}"))
                .hasNoCause().hasMessageNotContaining("PRIVATE_SECRET_SENTINEL");
    }

    @Test
    @DisplayName("连接 Nacos 时没有地址立即失败")
    void loadDocumentWithoutBootstrapFails() {
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(
                CmdbRemoteConfigSettings.defaults(),
                (Properties properties) -> Mockito.mock(ConfigService.class));

        Assertions.assertThatThrownBy(repository::loadDocument)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cwNacosServer");
    }
}
