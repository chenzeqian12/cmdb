package com.cloudwise.cmdb.config.remote;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    @DisplayName("从固定位置读取合法密文信封并关闭客户端")
    void loadEnvelopePasses() throws Exception {
        System.setProperty("cwNacosServer", "127.0.0.1:8848");
        ConfigService service = Mockito.mock(ConfigService.class);
        Mockito.when(service.getConfig("hkex-cmdb-sync-config.json", "hkex-cmdb-sync", 3000L))
                .thenReturn("{\"schemaVersion\":1,\"cipherType\":\"DOUC_SM2\",\"ciphertext\":\"abc\"}");
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(
                CmdbRemoteConfigSettings.defaults(), new ObjectMapper(), properties -> service);

        CmdbConfigEnvelope envelope = repository.loadEnvelope();

        Assertions.assertThat(envelope.ciphertext()).isEqualTo("abc");
        Mockito.verify(service).shutDown();
    }

    @Test
    @DisplayName("密文信封混入明文字段时拒绝")
    void loadEnvelopeWithUnknownFieldFails() throws Exception {
        System.setProperty("cwNacosServer", "127.0.0.1:8848");
        ConfigService service = Mockito.mock(ConfigService.class);
        Mockito.when(service.getConfig(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
                .thenReturn("{\"schemaVersion\":1,\"cipherType\":\"DOUC_SM2\","
                        + "\"ciphertext\":\"abc\",\"plaintext\":{}}");
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(
                CmdbRemoteConfigSettings.defaults(), new ObjectMapper(), properties -> service);

        Assertions.assertThatThrownBy(repository::loadEnvelope)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported field");
    }

    @Test
    @DisplayName("连接 Nacos 时没有地址立即失败")
    void loadEnvelopeWithoutBootstrapFails() {
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(
                CmdbRemoteConfigSettings.defaults(), new ObjectMapper(),
                (Properties properties) -> Mockito.mock(ConfigService.class));

        Assertions.assertThatThrownBy(repository::loadEnvelope)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cwNacosServer");
    }
}
