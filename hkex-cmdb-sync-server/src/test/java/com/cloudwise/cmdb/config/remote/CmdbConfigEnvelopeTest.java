package com.cloudwise.cmdb.config.remote;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CmdbConfigEnvelopeTest {

    @Test
    @DisplayName("只接受版本 1 的 DOUC_SM2 密文信封")
    void validateSupportedEnvelopePasses() {
        CmdbConfigEnvelope envelope = new CmdbConfigEnvelope(1, "DOUC_SM2", "ciphertext");

        Assertions.assertThatCode(envelope::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝空密文和未知算法")
    void validateInvalidEnvelopeFails() {
        Assertions.assertThatThrownBy(
                        () -> new CmdbConfigEnvelope(1, "AES", "ciphertext").validate())
                .isInstanceOf(IllegalStateException.class);
        Assertions.assertThatThrownBy(
                        () -> new CmdbConfigEnvelope(1, "DOUC_SM2", " ").validate())
                .isInstanceOf(IllegalStateException.class);
    }
}
