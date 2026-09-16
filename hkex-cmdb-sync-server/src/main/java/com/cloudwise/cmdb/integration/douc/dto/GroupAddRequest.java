package com.cloudwise.cmdb.integration.douc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code /group/add} 的请求体。
 *
 * <p>字段取自 HKEX dev 实测可用的 curl：
 * {@code {"type":1,"parentId":"1","leader":"","name":"111"}}。
 * 注意 {@code parentId} 是字符串而非数字，DOUC 侧按字符串解析。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class GroupAddRequest {

    /** 组类型，1 表示普通组。 */
    private int type;

    /** 父组 ID，字符串形式。 */
    private String parentId;

    /** 组长，可为空串。 */
    private String leader;

    /** 组名。 */
    private String name;
}
