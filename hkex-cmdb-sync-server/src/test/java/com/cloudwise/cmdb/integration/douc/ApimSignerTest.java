package com.cloudwise.cmdb.integration.douc;

import com.cloudwise.cmdb.common.exception.IntegrationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.Assertions;

/**
 * APIM 签名算法测试。
 *
 * <p>期望值由 Python gmssl / hashlib 独立算出，不是拿实现自证实现。</p>
 *
 * @author hkex-cmdb-sync
 */
class ApimSignerTest {

    private static final String APP_KEY = "REGbYeUtREGb";

    private static final String APP_SECRET = "1xhzuyt0xkrdB8oZ02lOgKvn330O1WRX";

    private static final String TIMESTAMP = "1730000000000";

    private static final String RANDOM = "abcdef0123456789";

    @Test
    @DisplayName("sm3 签名与服务端算法一致")
    void shouldSignWithSm3() {
        String sign = ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "sm3");

        Assertions.assertThat(sign).isEqualTo("48275ead9479d5805806f37cb818a4774c10df09218377c46dcdf16f500af036");
    }

    @Test
    @DisplayName("md5 签名兜底算法可用")
    void shouldSignWithMd5() {
        String sign = ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "md5");

        Assertions.assertThat(sign).isEqualTo("5c1aa35600d9d2b3e2551361e2b56fe2");
    }

    @Test
    @DisplayName("sha256 签名兜底算法可用")
    void shouldSignWithSha256() {
        String sign = ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "sha256");

        Assertions.assertThat(sign).isEqualTo("23e220b5960225d977752d30eb423f91a3c3cc19aa028a076622ab94e0e2af70");
    }

    @Test
    @DisplayName("签名算法为空时按 sm3 处理，与服务端默认值一致")
    void shouldDefaultToSm3WhenModeBlank() {
        Assertions.assertThat(ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, null))
                .isEqualTo(ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "sm3"));
        Assertions.assertThat(ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "  "))
                .isEqualTo(ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "sm3"));
    }

    @Test
    @DisplayName("算法名大小写不敏感")
    void shouldIgnoreCaseInMode() {
        Assertions.assertThat(ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "SM3"))
                .isEqualTo(ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "sm3"));
        Assertions.assertThat(ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "MD5"))
                .isEqualTo(ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "md5"));
    }

    @Test
    @DisplayName("拼串不带分隔符：任一段变化都会改变签名")
    void shouldDetectSegmentBoundaryChange() {
        // "ab"+"c" 与 "a"+"bc" 必须产生相同结果，证明是直接拼接而非带分隔符
        String joined = ApimSigner.sign("ab", "c", "", "", "md5");
        String shifted = ApimSigner.sign("a", "bc", "", "", "md5");

        Assertions.assertThat(joined).isEqualTo(shifted);
        // 而内容真正变化时必须不同
        Assertions.assertThat(ApimSigner.sign("ab", "c", "", "", "md5"))
                .isNotEqualTo(ApimSigner.sign("ab", "d", "", "", "md5"));
    }

    @Test
    @DisplayName("不支持的签名算法给出明确报错，而不是静默降级")
    void shouldRejectUnknownMode() {
        Assertions.assertThatThrownBy(() -> ApimSigner.sign(APP_KEY, APP_SECRET, TIMESTAMP, RANDOM, "sha1"))
                .isInstanceOf(IntegrationException.class)
                .hasMessageContaining("sha1")
                .hasMessageContaining("sm3");
    }
}
