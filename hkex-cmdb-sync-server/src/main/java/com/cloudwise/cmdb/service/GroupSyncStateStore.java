package com.cloudwise.cmdb.service;

import java.util.Map;

/**
 * 保存由同步服务创建并负责清理的 DOUC 用户组。
 *
 * @author hkex-cmdb-sync
 */
public interface GroupSyncStateStore {

    /**
     * 获取受本服务管理的组名到 DOUC 组 ID 的映射。
     *
     * @return 管理组快照
     */
    Map<String, Long> loadManagedGroups();

    /**
     * 保存管理组快照。
     *
     * @param groups 组名到 DOUC 组 ID 的映射
     */
    void saveManagedGroups(Map<String, Long> groups);
}
