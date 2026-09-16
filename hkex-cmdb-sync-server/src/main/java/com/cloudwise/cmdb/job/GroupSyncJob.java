package com.cloudwise.cmdb.job;

import com.cloudwise.cmdb.pojo.dto.GroupMemberPlan;
import com.cloudwise.cmdb.pojo.dto.GroupSyncResult;
import com.cloudwise.cmdb.service.GroupSyncService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * 每日用户组同步定时任务。
 *
 * <p>执行内容即需求 1 + 需求 2：把 SN 的 _SUP 用户组同步到 DOUC，
 * 并按组把用户配置进去。</p>
 *
 * <h2>cron 不在这个类里</h2>
 *
 * <p>本类只声明处理器的名字 {@value #HANDLER_NAME}。<b>执行时间在 XXL-Job 管理后台
 * 新建任务时填 cron 表达式</b>，每天 3 点对应：</p>
 *
 * <pre>
 * 0 0 3 * * ?
 * </pre>
 *
 * <p>后台新建任务时还需指定：执行器选 {@code xxl.job.executor.app.name} 配的那个、
 * 运行模式选 BEAN、JobHandler 填 {@value #HANDLER_NAME}。</p>
 *
 * <p>另外注意集成文档里写的是「Daily at 05:00」，与 3 点冲突，以实际需求为准，
 * 需要改只改后台的 cron，不用动代码。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
public class GroupSyncJob {

    /** 管理后台新建任务时要填的 JobHandler。 */
    public static final String HANDLER_NAME = "hkexCmdbGroupSyncJob";

    private final GroupSyncService groupSyncService;

    public GroupSyncJob(GroupSyncService groupSyncService) {
        this.groupSyncService = groupSyncService;
    }

    /**
     * 执行同步。
     *
     * <p>写不写数据由配置 {@code douc.dry-run} 决定；为 true 时只出计划不落库，
     * 会在调度日志里显著提示，避免"任务跑了但什么都没发生"。</p>
     */
    @XxlJob(HANDLER_NAME)
    public void syncGroupsAndMembers() {
        long startedAt = System.currentTimeMillis();
        XxlJobHelper.log("用户组同步开始");

        try {
            GroupSyncResult result = groupSyncService.sync(null, true, true);
            report(result);

            if (!result.getFailedGroups().isEmpty()) {
                // 有组建失败，任务标失败好让告警能触发；已成功的部分不回滚
                XxlJobHelper.handleFail("部分用户组创建失败：" + result.getFailedGroups());
                return;
            }
            if (result.isDryRun()) {
                XxlJobHelper.handleSuccess("试运行完成（未写数据），耗时 "
                        + (System.currentTimeMillis() - startedAt) + " ms");
                return;
            }
            XxlJobHelper.handleSuccess("同步完成，耗时 " + (System.currentTimeMillis() - startedAt) + " ms");
        } catch (Exception e) {
            log.error("用户组同步任务失败", e);
            XxlJobHelper.log("用户组同步异常：" + e.getMessage());
            XxlJobHelper.handleFail("同步失败：" + e.getMessage());
        }
    }

    /** 把结果写进调度日志，便于在管理后台直接看每组的增删情况。 */
    private void report(GroupSyncResult result) {
        XxlJobHelper.log(String.format(
                "源数据：组员记录 %d 条，脏记录 %d 条，源组 %d 个，源用户 %d 个",
                result.getSourceRecordCount(), result.getInvalidRecordCount(),
                result.getSourceGroupCount(), result.getSourceUserCount()));

        XxlJobHelper.log(String.format(
                "需求1 建组：新建 %d 个，已存在 %d 个，失败 %d 个",
                result.getCreatedGroups().size(), result.getExistingGroups().size(),
                result.getFailedGroups().size()));

        XxlJobHelper.log(String.format(
                "需求2 配人：匹配上 %d 人，未匹配 %d 人",
                result.getMatchedUserCount(), result.getUnmatchedUserCount()));

        if (result.isDryRun()) {
            XxlJobHelper.log("【注意】当前为 dryRun 试运行，上述均为计划，未写入 DOUC");
        }

        if (!result.getFailedGroups().isEmpty()) {
            XxlJobHelper.log("创建失败的组：" + result.getFailedGroups());
        }

        // 成员变动最多的 10 个组，方便快速判断本次同步的实际影响
        result.getMemberPlans().stream()
                .sorted(Comparator.comparingInt(
                        (GroupMemberPlan p) -> p.getToAdd().size() + p.getToRemove().size()).reversed())
                .limit(10)
                .filter(p -> !p.getToAdd().isEmpty() || !p.getToRemove().isEmpty())
                .forEach(p -> XxlJobHelper.log(String.format("组 %s：新增 %d 人，移除 %d 人",
                        p.getGroupName(), p.getToAdd().size(), p.getToRemove().size())));

        if (!result.getUnmatchedMemberSamples().isEmpty()) {
            XxlJobHelper.log("未匹配成员样本（最多 50 个）：" + result.getUnmatchedMemberSamples());
        }
    }
}
