package com.cloudwise.cmdb.integration.douc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DOUC 用户。
 *
 * <p>实测 {@code /user/list} 返回的账号字段有多个口径，映射 SN 用户时优先用
 * {@code email}，缺失时回退到 {@code userAlias}。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoucUser {

    /** 用户 ID。 */
    private Long id;

    /** 登录别名，可能是邮箱也可能是域账号。 */
    private String userAlias;

    /** 显示名。 */
    private String name;

    /** 邮箱，实测可能为 null。 */
    private String email;

    /** 展示用别名，形如 {@code user1(user1@example.com)}。 */
    private String userNameAlias;

    /** 状态，1 表示启用。 */
    private Integer status;
}
