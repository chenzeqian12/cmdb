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
     * <p>组名与邮箱都非空才算有效，这是后续建组与加人的最小前提。</p>
     *
     * @return true 表示可以参与同步
     */
    public boolean isValid() {
        return StringUtils.hasText(group) && StringUtils.hasText(userEmail);
    }
}
