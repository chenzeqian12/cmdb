package com.cloudwise.cmdb.integration.douc;

import com.cloudwise.cmdb.config.DoucProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

/**
 * DOUC 认证实现的公共基类。
 *
 * <p>三个实现（cookie / apim / direct）除了「怎么证明身份」不同，
 * <b>业务头是完全一样的</b>，所以抽到这里，避免三份拷贝各自漂移。</p>
 *
 * <p>业务头由服务端 {@code BasicThreadLocalInfoFilter} 读取并填入 ThreadLocal：
 * {@code accountId}、{@code topAccountId}、{@code userId}、{@code language}。
 * 组树与建组不依赖它们，但组内成员相关接口缺了会报 100001。</p>
 *
 * @author hkex-cmdb-sync
 */
abstract class AbstractDoucAuthProvider implements DoucAuthProvider {

    private static final String HEADER_ACCOUNT_ID = "accountId";

    private static final String HEADER_TOP_ACCOUNT_ID = "topAccountId";

    private static final String HEADER_USER_ID = "userId";

    private static final String HEADER_USER_NAME = "userName";

    private static final String HEADER_LANGUAGE = "language";

    protected final DoucProperties properties;

    protected AbstractDoucAuthProvider(DoucProperties properties) {
        this.properties = properties;
    }

    /**
     * 写入业务头。所有实现都应在设置完自身认证信息后调用。
     *
     * @param headers 待发送的请求头
     */
    protected void applyBusinessHeaders(HttpHeaders headers) {
        setIfPresent(headers, HEADER_ACCOUNT_ID, properties.getAccountId());
        setIfPresent(headers, HEADER_TOP_ACCOUNT_ID, properties.getTopAccountId());
        setIfPresent(headers, HEADER_USER_ID, properties.getUserId());
        setIfPresent(headers, HEADER_USER_NAME, properties.getUserName());
        setIfPresent(headers, HEADER_LANGUAGE, properties.getLanguage());
    }

    protected void setIfPresent(HttpHeaders headers, String name, String value) {
        if (StringUtils.hasText(value)) {
            headers.set(name, value);
        }
    }
}
