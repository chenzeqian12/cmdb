package com.cloudwise.cmdb.config.remote;

import com.cloudwise.douc.dto.resp.AsymmetricOut;
import com.cloudwise.douc.facadev3.AsymmetricSecurityFacade;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DoucCryptoClientTest {

    @Test
    @DisplayName("解密调用不设置 sessionId，走 DOUC 业务密钥分支")
    void decryptUsesBusinessKeyBranch() {
        AsymmetricSecurityFacade facade = Mockito.mock(AsymmetricSecurityFacade.class);
        Mockito.when(facade.decrypt(Mockito.any())).thenReturn(
                AsymmetricOut.builder().result("plaintext").build());
        DoucCryptoClient client = new DoucCryptoClient(facade, () -> { });

        String result = client.decrypt("ciphertext");

        ArgumentCaptor<com.cloudwise.douc.dto.req.AsymmetricIn> captor =
                ArgumentCaptor.forClass(com.cloudwise.douc.dto.req.AsymmetricIn.class);
        Mockito.verify(facade).decrypt(captor.capture());
        Assertions.assertThat(captor.getValue().getContent()).isEqualTo("ciphertext");
        Assertions.assertThat(captor.getValue().getSessionId()).isNull();
        Assertions.assertThat(result).isEqualTo("plaintext");
    }

    @Test
    void rpcFailureDoesNotExposeInputOrProviderCause() throws Exception {
        AsymmetricSecurityFacade facade = Mockito.mock(AsymmetricSecurityFacade.class);
        Mockito.when(facade.encrypt(Mockito.any())).thenThrow(
                new IllegalArgumentException("PRIVATE_SECRET_SENTINEL"));
        DoucCryptoClient client = new DoucCryptoClient(facade, () -> { });

        Assertions.assertThatThrownBy(() -> client.encrypt("PRIVATE_SECRET_SENTINEL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("PRIVATE_SECRET_SENTINEL")
                .hasNoCause();
    }

    @Test
    void bootstrapLoggingProtectionAndConnectionSettings() {
        DoucCryptoClient.protectBootstrapLogging();
        Assertions.assertThat(System.getProperty("nacos.logging.default.config.enabled")).isEqualTo("false");
        Assertions.assertThat(org.apache.logging.log4j.LogManager.getLogger("org.apache.dubbo").getLevel())
                .isEqualTo(org.apache.logging.log4j.Level.OFF);
        AsymmetricSecurityFacade facade = Mockito.mock(AsymmetricSecurityFacade.class);
        try (var references = Mockito.mockConstruction(org.apache.dubbo.config.ReferenceConfig.class,
                (reference, context) -> Mockito.when(reference.get()).thenReturn(facade))) {
            try (DoucCryptoClient client = DoucCryptoClient.connect("dummy-registry", "test-crypto")) {
                var reference = references.constructed().get(0);
                Mockito.verify(reference).setRetries(0);
                Mockito.verify(reference).setCheck(true);
                Mockito.verify(reference).setTimeout(15000);
                Mockito.verify(reference).setGroup("DOUC_RPC_DUBBO");
            }
            Mockito.verify(references.constructed().get(0)).destroy();
        }
    }

    @Test
    void connectionFailureAndEmptyInputFailSafely() {
        Assertions.assertThatThrownBy(() -> DoucCryptoClient.connect("", "test"))
                .hasMessageContaining("dubbo.registry.address");
        try (var references = Mockito.mockConstruction(org.apache.dubbo.config.ReferenceConfig.class,
                (reference, context) -> Mockito.when(reference.get()).thenThrow(
                        new IllegalStateException("PRIVATE_SECRET_SENTINEL")))) {
            Assertions.assertThatThrownBy(() -> DoucCryptoClient.connect("dummy-registry", "test"))
                    .hasNoCause().hasMessageNotContaining("PRIVATE_SECRET_SENTINEL");
            Mockito.verify(references.constructed().get(0)).destroy();
        }
        DoucCryptoClient client = new DoucCryptoClient(Mockito.mock(AsymmetricSecurityFacade.class), () -> { });
        Assertions.assertThatThrownBy(() -> client.encrypt(""))
                .hasMessageContaining("must not be empty");
    }

    @Test
    @DisplayName("DOUC 返回空结果时失败关闭")
    void decryptEmptyResultFails() {
        AsymmetricSecurityFacade facade = Mockito.mock(AsymmetricSecurityFacade.class);
        Mockito.when(facade.decrypt(Mockito.any())).thenReturn(new AsymmetricOut());
        DoucCryptoClient client = new DoucCryptoClient(facade, () -> { });

        Assertions.assertThatThrownBy(() -> client.decrypt("ciphertext"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }
}
