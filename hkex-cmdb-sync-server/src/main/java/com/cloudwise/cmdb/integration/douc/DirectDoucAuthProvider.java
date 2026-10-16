package com.cloudwise.cmdb.integration.douc;

import com.cloudwise.cmdb.config.DoucProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 直连 DOUC 服务、不做认证。
 *
 * <p>通过 {@code douc.auth-mode=direct} 激活，配合
 * {@code douc.base-url=http://<douc-host>:18241} 与
 * {@code douc.gateway-prefix=/douc} 使用。</p>
 *
 * <h2>为什么可以不做认证</h2>
 *
 * <p>2026-09-16 在现场服务器上实测确认：</p>
 * <ul>
 *   <li>走网关 {@code /docp/gateway/douc/**} 时，<b>网关强制校验 SSO 会话</b>，
 *       不带 cookie 直接 401；</li>
 *   <li>直连 DOUC 服务端口（{@code 10.106.3.75:18241}）时，<b>服务本身没有应用层鉴权</b>，
 *       建组 / 删组 / 查组全部 200 通过。</li>
 * </ul>
 *
 * <p>这与读 DOUC 源码的结论一致：{@code BasicInfoFilter} 只保护 {@code /admin/api/**}，
 * {@code BasicThreadLocalInfoFilter} 只把请求头塞进 ThreadLocal 而不校验。</p>
 *
 * <h2>安全提醒</h2>
 *
 * <p><b>这意味着任何能连到该端口的人都能冒充任意 accountId 往 DOUC 写数据。</b>
 * 是否接受由部署环境决定——本类只是如实反映现状，不代表这里做了任何防护。
 * 如果后续网关或网络策略收紧了直连，把 {@code douc.auth-mode} 切回 {@code apim} 即可，
 * 业务代码无需改动。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "douc.auth-mode", havingValue = "direct")
public class DirectDoucAuthProvider extends AbstractDoucAuthProvider {

    public DirectDoucAuthProvider(DoucProperties properties) {
        super(properties);
        log.warn("DOUC auth mode: direct, no authentication ({}). This is only appropriate for an internal"
                + " deployment that can reach the DOUC service port directly; make sure this is intended.",
                properties.getBaseUrl());
    }

    @Override
    public void applyAuth(HttpHeaders headers) {
        // 不做任何认证，只带业务头
        applyBusinessHeaders(headers);
    }
}
