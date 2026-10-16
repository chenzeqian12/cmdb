package com.cloudwise.cmdb.integration.servicenow;

import com.cloudwise.cmdb.config.ServiceNowProperties;
import com.cloudwise.cmdb.common.exception.IntegrationException;
import com.cloudwise.cmdb.integration.servicenow.dto.SnGroupMember;
import com.cloudwise.cmdb.integration.servicenow.dto.SnTableResponse;
import com.cloudwise.cmdb.integration.servicenow.dto.SnTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * ServiceNow 客户端。
 *
 * <p>只覆盖 HKEX CMDB 集成当前用到的两个能力：OAuth 换 token、读取用户组成员。
 * 两个接口都已在 UAT 实测通过。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
public class ServiceNowClient {

    private static final String TABLE_USER_GROUP_MEMBER = "/api/now/table/sys_user_grmember";

    /** 组员接口需要点号走引用的字段。 */
    private static final String MEMBER_FIELDS = "group,user.user_name,user.email";

    private final RestClient restClient;

    private final ServiceNowProperties properties;

    /** 缓存的 Bearer Token。 */
    private volatile String cachedToken;

    /** 缓存 Token 的过期时刻（epoch millis）。 */
    private volatile long tokenExpireAtMillis;

    public ServiceNowClient(RestClient serviceNowRestClient, ServiceNowProperties properties) {
        this.restClient = serviceNowRestClient;
        this.properties = properties;
    }

    /**
     * 拉取以 _SUP 结尾的用户组的所有组员。
     *
     * <p>返回的是扁平列表，同一个组会出现多条记录（每个组员一条），
     * 分组逻辑由上层 {@code GroupSyncService} 负责。</p>
     *
     * @return 组员列表，接口无数据时返回空列表
     */
    public List<SnGroupMember> fetchGroupMembers() {
        try {
            SnTableResponse<SnGroupMember> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(TABLE_USER_GROUP_MEMBER)
                            .queryParam("sysparm_query", properties.getGroupQuery())
                            // 显示引用字段的展示值，否则点号字段只会拿到 sys_id
                            .queryParam("sysparm_display_value", "true")
                            .queryParam("sysparm_exclude_reference_link", "true")
                            .queryParam("sysparm_fields", MEMBER_FIELDS)
                            .queryParam("sysparm_limit", properties.getPageSize())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<SnTableResponse<SnGroupMember>>() {
                    });

            if (response == null || response.getResult() == null) {
                log.warn("ServiceNow group member query returned no results, query={}",
                        properties.getGroupQuery());
                return Collections.emptyList();
            }
            log.info("ServiceNow group members fetched, records={}", response.getResult().size());
            return response.getResult();
        } catch (IntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new IntegrationException(
                    "Failed to fetch ServiceNow group members, query=" + properties.getGroupQuery(), e);
        }
    }

    /**
     * 获取可用的 Bearer Token，带本地缓存与提前刷新。
     *
     * @return Bearer Token
     */
    public String getAccessToken() {
        String token = cachedToken;
        if (StringUtils.hasText(token) && System.currentTimeMillis() < tokenExpireAtMillis) {
            return token;
        }
        return refreshAccessToken();
    }

    /**
     * 强制重新换取 Token。
     *
     * <p>加锁是因为同步任务可能并发触发，避免同时打多次换取请求。</p>
     *
     * @return 新的 Bearer Token
     */
    public synchronized String refreshAccessToken() {
        String token = cachedToken;
        if (StringUtils.hasText(token) && System.currentTimeMillis() < tokenExpireAtMillis) {
            return token;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("username", properties.getUsername());
        form.add("password", properties.getPassword());

        SnTokenResponse response;
        try {
            response = restClient.post()
                    .uri(properties.getTokenPath())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SnTokenResponse.class);
        } catch (Exception e) {
            throw new IntegrationException("Failed to exchange the ServiceNow access token", e);
        }

        // ServiceNow 换 token 失败时仍可能返回 HTTP 200，需要按响应体判断
        if (response == null || !StringUtils.hasText(response.getAccessToken())) {
            String detail = response == null
                    ? "empty response body"
                    : response.getError() + " / " + response.getErrorDescription();
            throw new IntegrationException("Failed to exchange the ServiceNow access token: " + detail);
        }

        long ttlSeconds = Math.max(0, response.getExpiresIn() - properties.getTokenRefreshAheadSeconds());
        cachedToken = response.getAccessToken();
        tokenExpireAtMillis = System.currentTimeMillis() + ttlSeconds * 1000L;
        log.info("ServiceNow access token refreshed, expires in {} seconds", response.getExpiresIn());
        return cachedToken;
    }
}
