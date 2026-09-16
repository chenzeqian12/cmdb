package com.cloudwise.cmdb.integration.douc.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * {@code /group/distribute} 的请求体。
 *
 * <p>对应 DOUC 服务端的 {@code GroupAndUserId}。服务端只读取
 * {@code id} 与 {@code userIdList} 两个字段：</p>
 *
 * <pre>
 * Set&lt;Long&gt; userIdSet = Sets.newHashSet(groupAndUserId.getUserIdList());
 * Set&lt;Long&gt; delUserIds = Sets.difference(oldUserIds, userIdSet);
 * </pre>
 *
 * <p><b>因此 {@code userIdList} 是「该组的完整期望成员」，不是增量。
 * 传 null 会在服务端直接 NPE（表现为 100001 系统错误）；传空列表会清空该组所有成员。</b></p>
 *
 * <p>DTO 里的 {@code userAliasList} 字段在本路径上未被使用——按别名同步要走
 * Dubbo 的 {@code synchronizeUserGroupUsersByUserAlias}，见 README。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberSetRequest {

    /** 组 ID。 */
    private Long id;

    /** 该组的完整成员用户 ID 列表，全量覆盖。 */
    private List<Long> userIdList;
}
