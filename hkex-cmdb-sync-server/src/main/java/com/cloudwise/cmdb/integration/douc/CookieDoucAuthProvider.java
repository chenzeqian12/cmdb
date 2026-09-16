package com.cloudwise.cmdb.integration.douc;

import com.cloudwise.cmdb.config.DoucProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 基于浏览器会话 Cookie 的 DOUC 认证实现（走网关时用）。
 *
 * <p>Cookie 取自浏览器开发者工具，包含 CASTGC、aops-key、aops-cpk、DOUC_SESSION 等。
 * <b>会话有有效期</b>，失效后接口返回 401，需要重新获取并更新配置
 * （实测放了几天就过期了）。</p>
 *
 * <p>因为会过期，这种方式<b>只适合人工联调</b>，不适合长期运行的定时任务。
 * 生产要么直连（{@code direct}），要么用应用签名（{@code apim}）。</p>
 *
 * <p>通过 {@code douc.auth-mode=cookie} 激活，未配置时默认生效。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "douc.auth-mode", havingValue = "cookie", matchIfMissing = true)
public class CookieDoucAuthProvider extends AbstractDoucAuthProvider {

    private static final String HEADER_ACCOUNT = "X-Prefer-Account";

    public CookieDoucAuthProvider(DoucProperties properties) {
        super(properties);
        if (!StringUtils.hasText(properties.getCookie())) {
            log.warn("未配置 douc.cookie，DOUC 接口调用会因缺少会话而失败");
        }
    }

    @Override
    public void applyAuth(HttpHeaders headers) {
        if (StringUtils.hasText(properties.getCookie())) {
            headers.set(HttpHeaders.COOKIE, properties.getCookie());
        }
        setIfPresent(headers, HEADER_ACCOUNT, properties.getAccountId());
        applyBusinessHeaders(headers);
    }
}
