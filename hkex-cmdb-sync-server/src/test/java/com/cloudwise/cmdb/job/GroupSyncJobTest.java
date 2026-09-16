package com.cloudwise.cmdb.job;

import com.cloudwise.cmdb.common.exception.IntegrationException;
import com.cloudwise.cmdb.pojo.dto.GroupMemberPlan;
import com.cloudwise.cmdb.pojo.dto.GroupSyncResult;
import com.cloudwise.cmdb.service.GroupSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;


/**
 * 定时任务单元测试。
 *
 * <p>{@code XxlJobHelper} 在任务上下文之外调用是安全的（空操作），
 * 因此这里只验证「调用参数正确」与「异常不外抛」两件事——
 * 抛出去的异常会让 XXL-Job 把任务标失败，但我们需要的是自己控制失败语义。</p>
 *
 * @author hkex-cmdb-sync
 */
@ExtendWith(MockitoExtension.class)
class GroupSyncJobTest {

    @Mock
    private GroupSyncService groupSyncService;

    private GroupSyncJob job;

    @BeforeEach
    void setUp() {
        job = new GroupSyncJob(groupSyncService);
    }

    @Test
    @DisplayName("按 dryRun=跟随配置、建组、配人 三个参数调用同步服务")
    void shouldInvokeSyncWithExpectedFlags() {
        Mockito.when(groupSyncService.sync(ArgumentMatchers.isNull(), ArgumentMatchers.any(Boolean.class), ArgumentMatchers.any(Boolean.class)))
                .thenReturn(new GroupSyncResult());

        job.syncGroupsAndMembers();

        // dryRun 传 null 表示跟随配置 douc.dry-run，由运维在配置层控制
        Mockito.verify(groupSyncService).sync(null, true, true);
    }

    @Test
    @DisplayName("同步抛异常时不外抛，避免调度器拿到未处理异常")
    void shouldSwallowException() {
        Mockito.when(groupSyncService.sync(ArgumentMatchers.isNull(), ArgumentMatchers.any(Boolean.class), ArgumentMatchers.any(Boolean.class)))
                .thenThrow(new IntegrationException("模拟网关不可达"));

        Assertions.assertThatCode(() -> job.syncGroupsAndMembers()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("结果里有失败组、成员计划、未匹配样本时都能正常报出，不抛异常")
    void shouldReportRichResultWithoutError() {
        GroupSyncResult result = new GroupSyncResult();
        result.setSourceRecordCount(807);
        result.setInvalidRecordCount(343);
        result.setSourceGroupCount(107);
        result.setSourceUserCount(342);
        result.getFailedGroups().add("Broken_SUP -> 模拟失败");
        result.setMatchedUserCount(300);
        result.setUnmatchedUserCount(42);
        result.getUnmatchedMemberSamples().addAll(List.of("a@x.com", "b@x.com"));

        GroupMemberPlan plan = new GroupMemberPlan();
        plan.setGroupName("GroupA_SUP");
        plan.getToAdd().addAll(List.of(1L, 2L));
        plan.getToRemove().add(9L);
        result.getMemberPlans().add(plan);

        Mockito.when(groupSyncService.sync(ArgumentMatchers.isNull(),
                ArgumentMatchers.any(Boolean.class), ArgumentMatchers.any(Boolean.class))).thenReturn(result);

        Assertions.assertThatCode(() -> job.syncGroupsAndMembers()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Handler 名字与文档一致，改名字等于改后台配置，需固定")
    void handlerNameShouldStayStable() {
        Assertions.assertThat(GroupSyncJob.HANDLER_NAME)
                .isEqualTo("hkexCmdbGroupSyncJob");
    }
}
