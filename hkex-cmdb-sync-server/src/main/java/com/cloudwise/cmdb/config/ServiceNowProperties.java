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

    /** ServiceNow 专用 HTTP 代理开关，旧配置缺省时保持原有连接方式。 */
    private boolean proxyEnabled = false;

    /** 代理主机名或 IPv4，不含协议、端口、路径及认证信息。 */
    private String proxyHost = "";

    /** HTTP 代理端口，HTTPS 目标通过 CONNECT 隧道访问。 */
    private int proxyPort = 8080;

    /**
     * 是否跳过 ServiceNow 的 HTTPS 证书校验（默认关闭）。
     *
     * <p>现场经公司代理访问 ServiceNow、而 ServiceNow 一侧的证书链不被 JDK 信任时开启。
     * 开启后不再校验对端证书与主机名，<b>ServiceNow 账号密码与 Token 可被链路上任何
     * 能应答该地址的一方读取</b>，仅应作为临时手段，证书补齐后立即关闭。</p>
     */
    private boolean allowInsecureTls = false;

    /** token 过期前提前刷新的秒数。 */
    private int tokenRefreshAheadSeconds = 300;

    /** 校验失败只报告字段名，不回显可能被误填到主机字段的凭据。 */
    public void validateProxy() {
        if (!proxyEnabled) {
            return;
        }
        if (proxyHost == null || proxyHost.length() > 253 || !proxyHost.matches(
                "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                        + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?")) {
            throw new IllegalStateException("servicenow.proxy-host must be a hostname or IPv4 address"
                    + " without scheme, port, path or credentials when proxy-enabled=true");
        }
        if (proxyPort < 1 || proxyPort > 65535) {
            throw new IllegalStateException("servicenow.proxy-port must be between 1 and 65535");
        }
    }
}
