package com.cloudwise.cmdb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ServiceNow 连接配置。
 *
 * <p>凭据来源于 HKEX UAT 的 OAuth 密码模式应用账号，使用密码授权换取 Bearer Token。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@ConfigurationProperties(prefix = "servicenow")
public class ServiceNowProperties {

    /** 实例地址，例如 https://hkexgroupuat.service-now.com */
    private String baseUrl;

    /** OAuth 换 token 的相对路径。 */
    private String tokenPath = "/oauth_token.do";

    /** OAuth client id。 */
    private String clientId;

    /** OAuth client secret。 */
    private String clientSecret;

    /** 集成账号用户名。 */
    private String username;

    /** 集成账号密码。 */
    private String password;

    /**
     * 用户组成员查询条件（sysparm_query 原文）。
     *
     * <p>默认取所有以 _SUP 结尾的用户组，与 HKEX CMDB 集成文档中
     * “User Group &lt;table=sys_user_grmember&gt;” 的范围一致。</p>
     */
    private String groupQuery = "group.nameENDSWITH_SUP";

    /** 单次分页条数。ServiceNow 默认返回全量，显式限制可避免一次拉取过大。 */
    private int pageSize = 5000;

    /** 连接超时（毫秒）。 */
    private int connectTimeout = 10000;

    /** 读取超时（毫秒）。全量 2 万+ 记录时需要留足。 */
    private int readTimeout = 120000;

    /** token 过期前提前刷新的秒数。 */
    private int tokenRefreshAheadSeconds = 300;
}
