package com.cloudwise.cmdb.service;

import com.cloudwise.cmdb.pojo.dto.GroupSyncResult;

/**
 * 用户组同步服务。
 *
 * <p>覆盖两个需求：</p>
 * <ol>
 *   <li>把 ServiceNow {@code sys_user_grmember} 中以 _SUP 结尾的用户组建到 DOUC；</li>
 *   <li>按这些用户组把 SN 的组员归类到 DOUC 用户。</li>
 * </ol>
 *
 * @author hkex-cmdb-sync
 */
public interface GroupSyncService {

    /**
     * 执行一次同步。
     *
     * @param dryRun       true 表示只计算差异不写数据；传 null 时取配置项 {@code douc.dry-run}
     * @param memberSync   是否执行需求 2（按组分类用户）。false 时只建组
     * @param includeUsers 是否拉取 DOUC 全量用户。memberSync 为 true 时必须为 true，
     *                     单独传 true 可用于只验证匹配率而不建组
     * @return 同步结果
     */
    GroupSyncResult sync(Boolean dryRun, boolean memberSync, boolean includeUsers);
}
