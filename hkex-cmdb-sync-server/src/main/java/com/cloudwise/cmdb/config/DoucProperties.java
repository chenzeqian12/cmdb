package com.cloudwise.cmdb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DOUC（用户中心）连接配置。
 *
 * <p>当前走网关 {@code /docp/gateway/douc/api/v1/**}，认证使用浏览器会话 Cookie。
 * 会话会过期，因此认证信息通过 {@link com.cloudwise.cmdb.integration.douc.DoucAuthProvider}
 * 抽象注入，后续换成正式应用鉴权只需替换实现类，无需改动业务代码。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@ConfigurationProperties(prefix = "douc")
public class DoucProperties {

    /** DOUC 站点地址，例如 https://hkexdev.cloudwise.com */
    private String baseUrl;

    /** 网关前缀。 */
    private String gatewayPrefix = "/docp/gateway/douc";

    /** API 版本前缀。 */
    private String apiPrefix = "/api/v1";

    /**
     * 认证方式，取值 {@code cookie}（默认）或 {@code apim}。
     *
     * <p>{@code cookie} 是浏览器会话，会过期，仅用于 UAT 联调；
     * 生产必须用 {@code apim}，见 {@link #appKey} / {@link #appSecret}。</p>
     */
    private String authMode = "cookie";

    // ---------- APIM 应用鉴权（生产） ----------

    /** APIM 应用 appKey。 */
    private String appKey;

    /** APIM 应用 appSecret。用于本地算签名，切勿外泄。 */
    private String appSecret;

    /**
     * 签名算法，对应服务端配置 {@code apim.sign.mode}。
     *
     * <p>兜底默认 sm3，与服务端 {code EncryptionUtil.encrypt} 的默认值一致。</p>
     */
    private String signMode = "sm3";

    /** 会话 Cookie 原文（不含 Cookie 头名）。仅 cookie 模式使用。 */
    private String cookie;

    /** 传给网关的账号 ID，对应 X-Prefer-Account。 */
    private String accountId = "110";

    /**
     * 租户 ID，对应请求头 {@code topAccountId}。
     *
     * <p>只读的组树/建组接口不需要它，但组内成员相关的接口要靠
     * {@code accountId/topAccountId/userId} 三个头填充服务端的 ThreadLocal，
     * 缺失时表现为 100001 系统错误。</p>
     */
    private String topAccountId = "110";

    /**
     * 操作人用户 ID，对应请求头 {@code userId}，默认 {@code 1}。
     *
     * <p>该值不只是"记操作日志"——{@code /user/list} 会<b>按调用人的可见范围过滤</b>。
     * 实测（2026-09-16，hkexdev）：{@code userId=1} 能看到全部用户，
     * {@code userId=3} 返回 0 条。填错的表现是匹配率静默归零，很难从现象反推到配置。</p>
     *
     * <p><b>换到生产环境时必须复核这一个值</b>：它是否同样有全局可见权限要以生产实测为准，
     * 可以用 {@code POST /sync/groups/match-check}（只读）跑一次确认。</p>
     */
    private String userId = "1";

    /** 操作人显示名，对应请求头 {@code userName}。 */
    private String userName = "cmdb-sync";

    /** 语言标识。 */
    private String language = "zh_CN";

    /**
     * 同步出来的用户组挂到哪个父组下。
     *
     * <p><b>留空表示平铺</b>：不挂任何父组，直接在顶层新建。
     * 服务端 {@code GroupServiceImpl.addGroup} 对 {@code parentId == null} 的处理是
     * {@code level = FIRST_LEVEL} 且不查父组，实测建出来的组 {@code groupIsTop=true}。</p>
     *
     * <p>填了值则必须是一个真实存在的组 ID，否则服务端找不到父组，报 ADD_ERROR。</p>
     */
    private String parentGroupId = "";

    /** DOUC 组的 type 字段，建组时必传，1 表示普通组。 */
    private int defaultGroupType = 1;

    /** 用户列表分页大小。 */
    private int userPageSize = 500;

    /** 连接超时（毫秒）。 */
    private int connectTimeout = 10000;

    /** 读取超时（毫秒）。 */
    private int readTimeout = 60000;

    /**
     * 是否开启试运行。开启后只做查询与差异计算，不产生任何写操作。
     *
     * <p>默认 true，避免误触把数据写进目标环境。</p>
     */
    private boolean dryRun = true;
}
