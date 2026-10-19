package com.cloudwise.cmdb.config;

import java.net.InetSocketAddress;
import java.net.Proxy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP 客户端配置。
 *
 * <p>ServiceNow 与 DOUC 各自独立一个 {@link RestClient}，因为两者的超时诉求不同：
 * ServiceNow 全量拉取 2 万+ 记录需要长读超时，DOUC 的组接口都是小请求。</p>
 *
 * <p>代理与「跳过证书校验」都只挂在 ServiceNow 客户端上，DOUC 保持直连且始终校验证书。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Configuration
public class HttpClientConfig {

    /**
     * ServiceNow 专用客户端。
     *
     * @param properties ServiceNow 配置
     * @return 已绑定 baseUrl 的客户端
     */
    @Bean
    public RestClient serviceNowRestClient(ServiceNowProperties properties) {
        properties.validateProxy();
        SimpleClientHttpRequestFactory factory = requestFactory(
                properties.getConnectTimeout(), properties.getReadTimeout(), properties.isAllowInsecureTls());
        if (properties.isProxyEnabled()) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(properties.getProxyHost(), properties.getProxyPort())));
        }
        if (properties.isAllowInsecureTls()) {
            log.warn("ServiceNow HTTPS certificate verification is DISABLED (servicenow.allow-insecure-tls=true);"
                    + " credentials and tokens on this route can be read by anyone able to answer for"
                    + " base-url={}. Import the issuing CA into the JDK truststore and turn this off",
                    properties.getBaseUrl());
        }
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * DOUC 专用客户端。
     *
     * @param properties DOUC 配置
     * @return 已绑定 baseUrl 的客户端
     */
    @Bean
    public RestClient doucRestClient(DoucProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory(properties.getConnectTimeout(), properties.getReadTimeout(), false))
                .build();
    }

    private SimpleClientHttpRequestFactory requestFactory(int connectTimeout, int readTimeout, boolean insecureTls) {
        SimpleClientHttpRequestFactory factory = insecureTls
                ? new InsecureTlsHttpRequestFactory()
                : new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
