package com.cloudwise.cmdb.integration.servicenow.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * {@code sys_user_grmember} 表的一条组员记录。
 *
 * <p>实测接口返回形如：</p>
 * <pre>
 * {"user.email":"AlbertLi@squareuat.com.hk",
 *  "group":"AE&amp;I-Application Integration_SUP",
 *  "user.user_name":"AlbertLi"}
 * </pre>
 *
 * <p>注意：SN 中用户被删除或引用失效时，{@code user.email} 与 {@code user.user_name}
 * 会返回空串，实测存在这类脏数据，同步前必须过滤。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnGroupMember {

    /** 组名，对应 SN 的 group.name。 */
    private String group;

    /** 用户邮箱，对应 SN 的 user.email（点号走引用）。 */
    @JsonProperty("user.email")
    private String userEmail;

    /** 用户名，对应 SN 的 user.user_name（点号走引用）。 */
    @JsonProperty("user.user_name")
    private String userName;

    /**
     * 是否为有效记录。
     *
     * <p>判定口径必须与同步链路一致，否则测试统计出来的「有效/无效」条数会和实际处理量对不上。
     * 实际口径在 {@link com.cloudwise.cmdb.pojo.dto.SourceMember#from}：
     * 组名非空，<b>且</b> {@code user_email}、{@code user_name} 至少有一个非空。</p>
     *
     * <p>注意早期版本要求邮箱必填，导致「有 user_name 无 email」的记录被当成脏数据丢弃，
     * 匹配面被白白缩小（生产上有 4 个邮箱被多个账号共用，必须靠 user_name 兜底）。</p>
     *
     * @return true 表示可以参与同步
     */
    public boolean isValid() {
        return StringUtils.hasText(group)
                && (StringUtils.hasText(userEmail) || StringUtils.hasText(userName));
    }
}
