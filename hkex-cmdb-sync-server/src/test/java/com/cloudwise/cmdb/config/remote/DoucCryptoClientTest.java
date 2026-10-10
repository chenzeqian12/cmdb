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
