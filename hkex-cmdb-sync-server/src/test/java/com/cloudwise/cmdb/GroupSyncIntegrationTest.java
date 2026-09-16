package com.cloudwise.cmdb;

import com.cloudwise.cmdb.integration.douc.DoucClient;
import com.cloudwise.cmdb.integration.servicenow.ServiceNowClient;
import com.cloudwise.cmdb.integration.servicenow.dto.SnGroupMember;
import com.cloudwise.cmdb.pojo.dto.GroupMemberPlan;
import com.cloudwise.cmdb.pojo.dto.GroupSyncResult;
import com.cloudwise.cmdb.service.GroupSyncService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;


/**
 * 真实环境只读联调。
 *
 * <p>会实际访问 ServiceNow 与 DOUC，默认不执行：</p>
 *
 * <pre>
 * mvn test -Dtest=GroupSyncIntegrationTest -Dit.enabled=true
 * </pre>
 *
 * <p><b>全部以 dryRun 运行，不会向 DOUC 写入任何数据。</b></p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "it.enabled", matches = "true")
class GroupSyncIntegrationTest {

    @Autowired
    private ServiceNowClient serviceNowClient;

    @Autowired
    private DoucClient doucClient;

    @Autowired
    private GroupSyncService groupSyncService;

    @Test
    @DisplayName("ServiceNow 换取 token 并拉到 _SUP 用户组成员")
    void shouldFetchGroupMembersFromServiceNow() {
        String token = serviceNowClient.getAccessToken();
        Assertions.assertThat(token).isNotBlank();

        List<SnGroupMember> members = serviceNowClient.fetchGroupMembers();
        Assertions.assertThat(members).isNotEmpty();

        long valid = members.stream().filter(SnGroupMember::isValid).count();
        log.info("SN 组员记录 {} 条，其中有效 {} 条，无效 {} 条",
                members.size(), valid, members.size() - valid);
    }

    @Test
    @DisplayName("DOUC 组树可读，返回组名到 ID 的映射")
    void shouldListDoucGroups() {
        Map<String, Long> groups = doucClient.listGroupNameToId();
        Assertions.assertThat(groups).isNotNull();
        log.info("DOUC 现有组 {} 个：{}", groups.size(), groups);
    }

    @Test
    @DisplayName("dryRun 全链路：建组计划 + 每组成员归类计划，不写入")
    void shouldPlanFullSyncWithoutWriting() {
        GroupSyncResult result = groupSyncService.sync(true, true, true);

        Assertions.assertThat(result.isDryRun()).isTrue();
        Assertions.assertThat(result.getSourceRecordCount()).isPositive();
        Assertions.assertThat(result.getSourceGroupCount()).isPositive();
        Assertions.assertThat(result.isMemberSyncExecuted()).isTrue();
        result.getMemberPlans().forEach(plan -> Assertions.assertThat(plan.isApplied()).isFalse());

        log.info("=== 源数据 ===");
        log.info("组员记录 {} 条，脏记录 {} 条，去重后源组 {} 个，源用户 {} 个",
                result.getSourceRecordCount(), result.getInvalidRecordCount(),
                result.getSourceGroupCount(), result.getSourceUserCount());

        log.info("=== 需求 1：同步用户组 ===");
        log.info("待新建组 {} 个，已存在 {} 个，失败 {} 个",
                result.getCreatedGroups().size(), result.getExistingGroups().size(),
                result.getFailedGroups().size());
        log.info("待新建组清单：{}", result.getCreatedGroups());

        log.info("=== 需求 2：按用户组分类用户 ===");
        log.info("源端邮箱匹配上 {} 个，未匹配 {} 个",
                result.getMatchedUserCount(), result.getUnmatchedUserCount());
        log.info("未匹配邮箱样本：{}", result.getUnmatchedMemberSamples());

        log.info("--- 成员最多的 10 个组 ---");
        // SLF4J 只认 {}，不支持 {:<45} 填充，用 String.format 自己排
        result.getMemberPlans().stream()
                .sorted(Comparator.comparingInt((GroupMemberPlan p) -> p.getSourceMembers().size()).reversed())
                .limit(10)
                .forEach(plan -> log.info(String.format(
                        "组 %-45s SN成员=%-4d 匹配=%-4d 未匹配=%-4d DOUC现有=%-4d 待增=%-4d 待删=%d",
                        plan.getGroupName(), plan.getSourceMembers().size(),
                        plan.getResolvedMembers().size(), plan.getUnmatchedMembers().size(),
                        plan.getExistingMemberCount(), plan.getToAdd().size(), plan.getToRemove().size())));

        int totalToAdd = result.getMemberPlans().stream().mapToInt(p -> p.getToAdd().size()).sum();
        int totalToRemove = result.getMemberPlans().stream().mapToInt(p -> p.getToRemove().size()).sum();
        log.info("汇总：所有组共待新增成员关系 {} 条，待移除 {} 条", totalToAdd, totalToRemove);
    }

    @Test
    @DisplayName("只验证匹配率：不建组不动成员")
    void shouldOnlyReportMatchRate() {
        GroupSyncResult result = groupSyncService.sync(true, false, true);

        Assertions.assertThat(result.isMemberSyncExecuted()).isFalse();
        Assertions.assertThat(result.getMemberPlans()).isEmpty();

        int matched = result.getMatchedUserCount();
        int unmatched = result.getUnmatchedUserCount();
        int total = matched + unmatched;
        log.info("匹配率：{}/{} （{}%）", matched, total,
                total == 0 ? 0 : Math.round(matched * 100.0 / total));
        log.info("未匹配邮箱样本（最多 50 个）：{}", result.getUnmatchedMemberSamples());
    }
}
