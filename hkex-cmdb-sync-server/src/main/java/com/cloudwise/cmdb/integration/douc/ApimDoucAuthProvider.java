package com.cloudwise.cmdb.integration.douc;

import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.common.exception.IntegrationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 基于 APIM 应用凭证的 DOUC 认证实现 —— 生产环境用这个。
 *
 * <p>用 appKey/appSecret 做请求签名，不依赖任何会话，密钥可轮换、不自然过期，
 * 适合长期运行的同步任务。</p>
 *
 * <p>四个请求头的语义与服务端 {@code apim/apimServer/.../service/ApiService.java:357}
 * 一致：</p>
 *
 * <table border="1">
 *   <caption>鉴权头</caption>
 *   <tr><th>头名</th><th>说明</th></tr>
 *   <tr><td>{@code appKey}</td><td>应用标识</td></tr>
 *   <tr><td>{@code timestamp}</td><td>毫秒时间戳，服务端校验有效期（默认 180s），
 *       因此<b>依赖本机时钟</b>，机器时间偏差过大会被拒</td></tr>
 *   <tr><td>{@code random}</td><td>一次性随机串，服务端防重放</td></tr>
 *   <tr><td>{@code appSign}</td><td>见 {@link ApimSigner}</td></tr>
 * </table>
 *
 * <p>通过 {@code douc.auth-mode=apim} 激活。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "douc.auth-mode", havingValue = "apim")
public class ApimDoucAuthProvider extends AbstractDoucAuthProvider {

    private static final String HEADER_APP_KEY = "appKey";

    private static final String HEADER_APP_SIGN = "appSign";

    private static final String HEADER_TIMESTAMP = "timestamp";

    private static final String HEADER_RANDOM = "random";

    /** 随机串字节数，16 字节 = 32 位十六进制，足够避免碰撞。 */
    private static final int NONCE_BYTES = 16;

    private final SecureRandom secureRandom = new SecureRandom();

    public ApimDoucAuthProvider(DoucProperties properties) {
        super(properties);
        if (!StringUtils.hasText(properties.getAppKey()) || !StringUtils.hasText(properties.getAppSecret())) {
            // 快速失败：配了 apim 模式却没给凭证，任何请求都会 401，不如启动就说清楚
            throw new IntegrationException(
                    "douc.auth-mode=apim 但未配置 douc.app-key / douc.app-secret");
        }
        log.info("DOUC 认证方式：APIM 应用签名（appKey={}，签名算法={}）",
                properties.getAppKey(), properties.getSignMode());
    }

    @Override
    public void applyAuth(HttpHeaders headers) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String random = newNonce();
        String sign = ApimSigner.sign(properties.getAppKey(), properties.getAppSecret(),
                timestamp, random, properties.getSignMode());

        headers.set(HEADER_APP_KEY, properties.getAppKey());
        headers.set(HEADER_TIMESTAMP, timestamp);
        headers.set(HEADER_RANDOM, random);
        headers.set(HEADER_APP_SIGN, sign);

        applyBusinessHeaders(headers);
    }

    private String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

}
