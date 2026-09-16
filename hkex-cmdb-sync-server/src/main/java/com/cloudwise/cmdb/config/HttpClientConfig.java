package com.cloudwise.cmdb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP 客户端配置。
 *
 * <p>ServiceNow 与 DOUC 各自独立一个 {@link RestClient}，因为两者的超时诉求不同：
 * ServiceNow 全量拉取 2 万+ 记录需要长读超时，DOUC 的组接口都是小请求。</p>
 *
 * @author hkex-cmdb-sync
 */
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
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory(properties.getConnectTimeout(), properties.getReadTimeout()))
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
                .requestFactory(requestFactory(properties.getConnectTimeout(), properties.getReadTimeout()))
                .build();
    }

    private ClientHttpRequestFactory requestFactory(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
