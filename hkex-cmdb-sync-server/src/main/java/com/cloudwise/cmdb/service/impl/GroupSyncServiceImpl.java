package com.cloudwise.cmdb.service.impl;

import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.common.exception.IntegrationException;
import com.cloudwise.cmdb.integration.douc.DoucClient;
import com.cloudwise.cmdb.integration.douc.dto.DoucGroupMember;
import com.cloudwise.cmdb.integration.douc.dto.DoucUser;
import com.cloudwise.cmdb.integration.servicenow.ServiceNowClient;
import com.cloudwise.cmdb.integration.servicenow.dto.SnGroupMember;
import com.cloudwise.cmdb.pojo.dto.GroupMemberPlan;
import com.cloudwise.cmdb.pojo.dto.GroupSyncResult;
import com.cloudwise.cmdb.pojo.dto.SourceMember;
import com.cloudwise.cmdb.service.GroupSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 用户组同步实现。
 *
 * <p>处理链路：拉取 SN 组员 → 清洗去重 → 建出缺失的组 → 拉 DOUC 用户建别名索引 →
 * 按组解析成员 → 全量覆盖写回。</p>
 *
 * <p><b>幂等性：</b>以组名判重建组；成员按「SN 为唯一数据源」全量覆盖写入，
 * 重复执行结果一致，不会累积。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Service
public class GroupSyncServiceImpl implements GroupSyncService {

    /** 未匹配成员在结果里保留的样本数量上限，避免响应体过大。 */
    private static final int UNMATCHED_SAMPLE_LIMIT = 50;

    private final ServiceNowClient serviceNowClient;

    private final DoucClient doucClient;

    private final DoucProperties doucProperties;

    public GroupSyncServiceImpl(ServiceNowClient serviceNowClient,
                                DoucClient doucClient,
                                DoucProperties doucProperties) {
        this.serviceNowClient = serviceNowClient;
        this.doucClient = doucClient;
        this.doucProperties = doucProperties;
    }

    @Override
    public GroupSyncResult sync(Boolean dryRun, boolean memberSync, boolean includeUsers) {
        final long startedAt = System.currentTimeMillis();
        GroupSyncResult result = new GroupSyncResult();
        result.setDryRun(dryRun == null ? doucProperties.isDryRun() : dryRun);

        // ---------- 源数据 ----------
        List<SnGroupMember> members = serviceNowClient.fetchGroupMembers();
        result.setSourceRecordCount(members.size());

        Map<String, Set<SourceMember>> sourceGroups = groupMembersByGroupName(members, result);
        result.setSourceGroupCount(sourceGroups.size());
        Set<SourceMember> allMembers = distinctMembers(sourceGroups);
        result.setSourceUserCount(allMembers.size());

        // ---------- 需求 1：建组 ----------
        Map<String, Long> existingGroups = doucClient.listGroupNameToId();
        log.info("DOUC 现有组 {} 个，源端组 {} 个，dryRun={}",
                existingGroups.size(), sourceGroups.size(), result.isDryRun());
        Map<String, Long> groupIds = createMissingGroups(sourceGroups, existingGroups, result);

        // ---------- 需求 2：按组分类用户 ----------
        if (memberSync || includeUsers) {
            Map<String, Long> aliasToUserId = buildUserAliasIndex(result, allMembers);
            if (memberSync) {
                syncMembers(sourceGroups, groupIds, aliasToUserId, result);
            }
        }

        result.setElapsedMillis(System.currentTimeMillis() - startedAt);
        log.info("用户组同步结束，{}，耗时 {} ms", summary(result), result.getElapsedMillis());
        return result;
    }

    /**
     * 按组名归类源成员，同时剔除脏记录。
     *
     * @return 组名 -&gt; 去重后的成员集合，按组名排序
     */
    private Map<String, Set<SourceMember>> groupMembersByGroupName(List<SnGroupMember> members,
                                                                   GroupSyncResult result) {
        Map<String, Set<SourceMember>> grouped = new TreeMap<>();
        int invalid = 0;

        for (SnGroupMember member : members) {
            SourceMember source = SourceMember.from(member);
            if (source == null || source.candidateKeys().isEmpty()) {
                invalid++;
                continue;
            }
            grouped.computeIfAbsent(member.getGroup().trim(), key -> new LinkedHashSet<>()).add(source);
        }

        result.setInvalidRecordCount(invalid);
        if (invalid > 0) {
            log.warn("丢弃 {} 条组名或用户标识为空的组员记录", invalid);
        }
        return grouped;
    }

    private Set<SourceMember> distinctMembers(Map<String, Set<SourceMember>> sourceGroups) {
        Set<SourceMember> all = new LinkedHashSet<>();
        sourceGroups.values().forEach(all::addAll);
        return all;
    }

    /**
     * 建出 DOUC 中尚不存在的组，返回「组名 -&gt; 组 ID」。
     *
     * <p>建组接口成功时不返回新组 ID，所以创建后统一回查一次组树补齐映射。</p>
     */
    private Map<String, Long> createMissingGroups(Map<String, Set<SourceMember>> sourceGroups,
                                                  Map<String, Long> existingGroups,
                                                  GroupSyncResult result) {
        List<String> toCreate = new ArrayList<>();
        for (String groupName : sourceGroups.keySet()) {
            if (existingGroups.containsKey(groupName)) {
                result.getExistingGroups().add(groupName);
            } else {
                result.getCreatedGroups().add(groupName);
                toCreate.add(groupName);
            }
        }

        if (result.isDryRun() || toCreate.isEmpty()) {
            return new LinkedHashMap<>(existingGroups);
        }

        // 空表示平铺到顶层，服务端按 FIRST_LEVEL 处理；有值则必须是真实存在的组 ID
        String parentGroupId = StringUtils.hasText(doucProperties.getParentGroupId())
                ? doucProperties.getParentGroupId().trim()
                : null;
        if (parentGroupId == null) {
            log.info("douc.parent-group-id 为空，用户组将平铺在顶层创建");
        }

        Set<String> created = new LinkedHashSet<>();
        for (String groupName : toCreate) {
            try {
                doucClient.addGroup(groupName, parentGroupId);
                created.add(groupName);
            } catch (IntegrationException e) {
                // 单组失败不中断整体同步，记录后继续
                result.getFailedGroups().add(groupName + " -> " + e.getMessage());
                result.getCreatedGroups().remove(groupName);
                log.error("创建 DOUC 用户组失败，name={}", groupName, e);
            }
        }

        Map<String, Long> groupIds = new LinkedHashMap<>(existingGroups);
        if (!created.isEmpty()) {
            // 回查组树拿到新建组的 ID
            Map<String, Long> refreshed = doucClient.listGroupNameToId();
            for (String groupName : created) {
                Long id = refreshed.get(groupName);
                if (id == null) {
                    result.getFailedGroups().add(groupName + " -> 建组后回查未找到该组");
                } else {
                    groupIds.put(groupName, id);
                    log.info("已创建 DOUC 用户组，name={}，id={}", groupName, id);
                }
            }
        }
        return groupIds;
    }

    /**
     * 拉取 DOUC 全量用户，建立「归一化别名/邮箱 -&gt; 用户 ID」索引。
     *
     * <p>DOUC 的 {@code email} 字段实测常为 null，{@code userAlias} 才是主要口径。</p>
     * <p>{@code User.code}（即 Dubbo 同步接口里的 userCode）存在于模型，
     * 但未在 {@code UserListResponseObject} 中暴露，HTTP 侧读不到，因此无法用它做匹配。</p>
     */
    private Map<String, Long> buildUserAliasIndex(GroupSyncResult result, Set<SourceMember> sourceMembers) {
        List<DoucUser> doucUsers = doucClient.listAllUsers();
        Map<String, Long> index = new LinkedHashMap<>();
        for (DoucUser user : doucUsers) {
            putIfAbsent(index, user.getEmail(), user.getId());
            putIfAbsent(index, user.getUserAlias(), user.getId());
        }

        // 判定必须与 syncMembers 里的解析口径一致：逐个试候选键，命中任一即算匹配。
        // 只在 displayKey 上判定会漏掉「邮箱对不上但 user_name 对得上」的成员，把匹配率算低。
        int matched = 0;
        List<String> unmatched = new ArrayList<>();
        for (SourceMember member : sourceMembers) {
            if (resolveUserId(member, index) != null) {
                matched++;
            } else {
                unmatched.add(member.displayKey());
                if (result.getUnmatchedMemberSamples().size() < UNMATCHED_SAMPLE_LIMIT) {
                    result.getUnmatchedMemberSamples().add(member.displayKey());
                }
            }
        }

        result.setMatchedUserCount(matched);
        result.setUnmatchedUserCount(unmatched.size());
        log.info("DOUC 用户 {} 条，索引 {} 项；源端 {} 个成员中匹配上 {} 个，未匹配 {} 个",
                doucUsers.size(), index.size(), sourceMembers.size(), matched, unmatched.size());
        return index;
    }

    private void putIfAbsent(Map<String, Long> index, String alias, Long userId) {
        if (StringUtils.hasText(alias) && userId != null) {
            index.putIfAbsent(normalize(alias), userId);
        }
    }

    /**
     * 按组把 SN 的成员对应到 DOUC 用户，并全量覆盖写回。
     *
     * <p>写之前先读 DOUC 现有成员，算出增删差异记进计划，
     * 这样 dryRun 和实跑都能看到「会加谁、会删谁」。</p>
     */
    private void syncMembers(Map<String, Set<SourceMember>> sourceGroups,
                             Map<String, Long> groupIds,
                             Map<String, Long> aliasToUserId,
                             GroupSyncResult result) {
        result.setMemberSyncExecuted(true);

        for (Map.Entry<String, Set<SourceMember>> entry : sourceGroups.entrySet()) {
            String groupName = entry.getKey();
            GroupMemberPlan plan = new GroupMemberPlan();
            plan.setGroupName(groupName);
            result.getMemberPlans().add(plan);

            Long groupId = groupIds.get(groupName);
            plan.setGroupId(groupId);
            plan.setGroupExisted(groupId != null && result.getExistingGroups().contains(groupName));

            // 解析成员不依赖组是否已存在，先无条件算出来，
            // 这样 dryRun 下新建组也能看到「该分哪些人进去」。
            Set<Long> targetUserIds = new LinkedHashSet<>();
            for (SourceMember member : entry.getValue()) {
                plan.getSourceMembers().add(member.displayKey());
                Long userId = resolveUserId(member, aliasToUserId);
                if (userId == null) {
                    plan.getUnmatchedMembers().add(member.displayKey());
                } else {
                    plan.getResolvedMembers().add(member.displayKey());
                    targetUserIds.add(userId);
                }
            }
            plan.getTargetUserIds().addAll(targetUserIds);

            if (groupId == null) {
                // dryRun 时新建组还没有 ID；实跑时说明建组失败了
                plan.setSkippedReason(result.isDryRun()
                        ? "dryRun：组尚未创建，仅出成员计划"
                        : "组未建立，跳过成员归类");
                continue;
            }

            // 与 DOUC 现状比对，算出增删
            Set<Long> existingIds = new LinkedHashSet<>();
            for (DoucGroupMember member : doucClient.listGroupMembers(groupId)) {
                if (member.getUserId() != null) {
                    existingIds.add(member.getUserId());
                }
            }
            plan.setExistingMemberCount(existingIds.size());

            targetUserIds.stream().filter(id -> !existingIds.contains(id)).forEach(plan.getToAdd()::add);
            existingIds.stream().filter(id -> !targetUserIds.contains(id)).forEach(plan.getToRemove()::add);

            if (result.isDryRun()) {
                plan.setSkippedReason("dryRun，未写入");
                continue;
            }

            doucClient.setGroupMembers(groupId, plan.getTargetUserIds());
            plan.setApplied(true);
        }
    }

    /** 依次尝试成员的候选键，命中第一个即返回。 */
    private Long resolveUserId(SourceMember member, Map<String, Long> aliasToUserId) {
        for (String key : member.candidateKeys()) {
            Long userId = aliasToUserId.get(key);
            if (userId != null) {
                return userId;
            }
        }
        return null;
    }

    /** 标识统一小写去空格，避免同一账号因大小写或空格差异被判为两个。 */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String summary(GroupSyncResult result) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("源记录", result.getSourceRecordCount());
        parts.put("脏记录", result.getInvalidRecordCount());
        parts.put("源组", result.getSourceGroupCount());
        parts.put("新建组", result.getCreatedGroups().size());
        parts.put("已存在组", result.getExistingGroups().size());
        parts.put("失败组", result.getFailedGroups().size());
        if (result.isMemberSyncExecuted()) {
            parts.put("匹配用户", result.getMatchedUserCount());
            parts.put("未匹配", result.getUnmatchedUserCount());
            parts.put("处理组数", result.getMemberPlans().size());
        }
        return parts.toString();
    }
}
