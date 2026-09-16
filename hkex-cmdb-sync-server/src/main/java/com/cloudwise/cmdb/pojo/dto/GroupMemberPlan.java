package com.cloudwise.cmdb.pojo.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个用户组的成员同步计划与结果。
 *
 * <p>服务端的 {@code /group/distribute} 是全量覆盖语义，因此「计划」和「结果」是同一份数据：
 * 计划里 {@link #targetUserIds} 就是将要写入（或已写入）的完整成员列表。</p>
 *
 * <p>dryRun 时 {@link #toAdd} / {@link #toRemove} 是预演；实际执行后它们就是本次的变更。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
public class GroupMemberPlan {

    /** 组名。 */
    private String groupName;

    /** DOUC 中的组 ID。组没建成时为 null。 */
    private Long groupId;

    /** 组在本次同步前是否已存在于 DOUC。 */
    private boolean groupExisted;

    /**
     * SN 侧该组的成员标识（已清洗、去重）。
     *
     * <p>展示用，取值见 {@link SourceMember#displayKey()}，优先邮箱。</p>
     */
    private final List<String> sourceMembers = new ArrayList<>();

    /** 能匹配到 DOUC 用户的成员标识。 */
    private final List<String> resolvedMembers = new ArrayList<>();

    /** 匹配不到 DOUC 用户的成员标识。这些人无法归类，需要关注。 */
    private final List<String> unmatchedMembers = new ArrayList<>();

    /** 将要写入的完整成员 ID 列表，即全量覆盖的目标状态。 */
    private final List<Long> targetUserIds = new ArrayList<>();

    /** 本次相对 DOUC 现状会新增的成员。 */
    private final List<Long> toAdd = new ArrayList<>();

    /** 本次相对 DOUC 现状会移除的成员（全量覆盖语义下才会非空）。 */
    private final List<Long> toRemove = new ArrayList<>();

    /** 同步前 DOUC 该组的成员数。 */
    private int existingMemberCount;

    /** 是否已实际写入。dryRun 或组创建失败时为 false。 */
    private boolean applied;

    /** 未写入的原因。 */
    private String skippedReason;
}
