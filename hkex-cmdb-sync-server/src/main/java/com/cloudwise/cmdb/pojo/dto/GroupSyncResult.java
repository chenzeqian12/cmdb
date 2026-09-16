package com.cloudwise.cmdb.pojo.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户组同步结果。
 *
 * <p>覆盖两个需求的完整结果：先按 SN 建出用户组，再把 SN 的组员按组归类到 DOUC 用户。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
public class GroupSyncResult {

    /** 是否为试运行。true 时没有任何写操作，各列表记录的是「本会执行」的内容。 */
    private boolean dryRun;

    // ---------- 源数据概况 ----------

    /** ServiceNow 返回的原始组员记录数。 */
    private int sourceRecordCount;

    /** 因组名或邮箱为空被丢弃的记录数。实测 UAT 存在这类脏数据，占比很高。 */
    private int invalidRecordCount;

    /** 去重后的源组数量。 */
    private int sourceGroupCount;

    /** 去重后的源用户邮箱数量。 */
    private int sourceUserCount;

    // ---------- 需求 1：同步用户组 ----------

    /** 本次新建的组名。 */
    private final List<String> createdGroups = new ArrayList<>();

    /** 已存在因而跳过的组名。 */
    private final List<String> existingGroups = new ArrayList<>();

    /** 创建失败的组：组名 -> 失败原因。 */
    private final List<String> failedGroups = new ArrayList<>();

    // ---------- 需求 2：按用户组分类用户 ----------

    /** 每个组的成员归类计划与结果。 */
    private final List<GroupMemberPlan> memberPlans = new ArrayList<>();

    /** 是否执行了成员归类。关掉可只建组不动成员。 */
    private boolean memberSyncExecuted;

    /** 匹配到 DOUC 用户的成员标识数（跨组去重）。 */
    private int matchedUserCount;

    /** 匹配不到 DOUC 用户的成员标识数（跨组去重）。 */
    private int unmatchedUserCount;

    /**
     * 匹配不上的成员标识样本，最多保留 N 个，避免响应体过大。
     *
     * <p>取值是 {@link SourceMember#displayKey()}，即邮箱优先、否则用户名。</p>
     */
    private final List<String> unmatchedMemberSamples = new ArrayList<>();

    /** 耗时，毫秒。 */
    private long elapsedMillis;
}
