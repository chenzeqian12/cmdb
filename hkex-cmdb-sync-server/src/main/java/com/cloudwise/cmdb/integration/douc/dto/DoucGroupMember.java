package com.cloudwise.cmdb.integration.douc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 组内成员。
 *
 * <p>{@code /group/getUserList/{groupId}} 的返回元素。实测多数字段为 null，
 * 只有 {@code userId} 与 {@code userName} 有值。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoucGroupMember {

    /** 用户 ID。 */
    private Long userId;

    /** 用户显示名。 */
    private String userName;

    /** 用户别名。 */
    private String userAlias;

    /** 邮箱。 */
    private String email;
}
