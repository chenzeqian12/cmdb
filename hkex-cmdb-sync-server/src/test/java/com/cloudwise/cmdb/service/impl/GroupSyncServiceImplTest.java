package com.cloudwise.cmdb.service.impl;

import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.service.GroupSyncStateStore;
import com.cloudwise.cmdb.common.exception.IntegrationException;
import com.cloudwise.cmdb.integration.douc.DoucClient;
import com.cloudwise.cmdb.integration.douc.dto.DoucGroupMember;
import com.cloudwise.cmdb.integration.douc.dto.DoucUser;
import com.cloudwise.cmdb.integration.servicenow.ServiceNowClient;
import com.cloudwise.cmdb.integration.servicenow.dto.SnGroupMember;
import com.cloudwise.cmdb.pojo.dto.GroupMemberPlan;
import com.cloudwise.cmdb.pojo.dto.GroupSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;


/**
 * 用户组同步逻辑单元测试。
 *
 * <p>覆盖清洗、判重、幂等、失败隔离，以及「按用户组分类用户」的解析与全量覆盖语义。
 * 全部使用 mock 客户端，不触网。</p>
 *
 * @author hkex-cmdb-sync
 */
@ExtendWith(MockitoExtension.class)
class GroupSyncServiceImplTest {

    @Mock
    private ServiceNowClient serviceNowClient;

    @Mock
    private DoucClient doucClient;

    @Mock
    private GroupSyncStateStore groupSyncStateStore;

    private DoucProperties doucProperties;

    private GroupSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        doucProperties = new DoucProperties();
        doucProperties.setParentGroupId("1");
        doucProperties.setDryRun(true);
        service = new GroupSyncServiceImpl(serviceNowClient, doucClient, doucProperties,
                groupSyncStateStore);
    }

    private SnGroupMember member(String group, String email) {
        SnGroupMember member = new SnGroupMember();
        member.setGroup(group);
        member.setUserEmail(email);
        member.setUserName(email);
        return member;
    }

    private DoucUser user(long id, String alias) {
        DoucUser user = new DoucUser();
        user.setId(id);
        user.setUserAlias(alias);
        user.setName(alias);
        return user;
    }

    private DoucGroupMember groupMember(long userId) {
        DoucGroupMember member = new DoucGroupMember();
        member.setUserId(userId);
        return member;
    }

    private GroupMemberPlan planOf(GroupSyncResult result, String groupName) {
        return result.getMemberPlans().stream()
                .filter(p -> groupName.equals(p.getGroupName()))
                .findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------
    // 需求 1：同步用户组
    // ------------------------------------------------------------------

    @Test
    @DisplayName("组名或邮箱为空的脏记录被丢弃，不计入组与用户统计")
    void shouldDropInvalidRecords() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("AI_SUP", "AlbertLi@squareuat.com.hk"),
                member("", "nobody@example.com"),
                member("AI_SUP", ""),
                member("AI_SUP", "")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of());

        GroupSyncResult result = service.sync(true, false, false);

        Assertions.assertThat(result.getSourceRecordCount()).isEqualTo(4);
        Assertions.assertThat(result.getInvalidRecordCount()).isEqualTo(3);
        Assertions.assertThat(result.getSourceGroupCount()).isEqualTo(1);
        Assertions.assertThat(result.getSourceUserCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dryRun 下只算差异，不调用建组接口")
    void shouldNotWriteWhenDryRun() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "a@example.com"),
                member("GroupB_SUP", "b@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of());

        GroupSyncResult result = service.sync(true, false, false);

        Assertions.assertThat(result.isDryRun()).isTrue();
        Assertions.assertThat(result.getCreatedGroups()).containsExactly("GroupA_SUP", "GroupB_SUP");
        Mockito.verify(doucClient, Mockito.never()).addGroup(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("已存在的组不重复创建，只创建缺失的组")
    void shouldOnlyCreateMissingGroups() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "a@example.com"),
                member("GroupB_SUP", "b@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 5L));

        GroupSyncResult result = service.sync(false, false, false);

        Assertions.assertThat(result.getCreatedGroups()).containsExactly("GroupB_SUP");
        Assertions.assertThat(result.getExistingGroups()).containsExactly("GroupA_SUP");
        Mockito.verify(doucClient, Mockito.times(1)).addGroup("GroupB_SUP", "1");
        Mockito.verify(doucClient, Mockito.never()).addGroup("GroupA_SUP", "1");
    }

    @Test
    @DisplayName("正式同步时删除上次创建但本次 SN 已不存在的组")
    void shouldDeletePreviouslyManagedGroupsMissingFromServiceNow() {
        doucProperties.setDeleteMissingGroups(true);
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "a@example.com"),
                member("GroupB_SUP", "b@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of(
                "GroupA_SUP", 1L, "GroupB_SUP", 2L, "GroupC_SUP", 3L));
        Mockito.when(groupSyncStateStore.loadManagedGroups()).thenReturn(Map.of(
                "GroupA_SUP", 1L, "GroupB_SUP", 2L, "GroupC_SUP", 3L));

        GroupSyncResult result = service.sync(false, false, false);

        Assertions.assertThat(result.getGroupsToDelete()).containsExactly("GroupC_SUP");
        Assertions.assertThat(result.getDeletedGroups()).containsExactly("GroupC_SUP");
        Mockito.verify(doucClient).deleteGroup(3L);
        Mockito.verify(groupSyncStateStore).saveManagedGroups(Map.of(
                "GroupA_SUP", 1L, "GroupB_SUP", 2L));
    }

    @Test
    @DisplayName("单个组创建失败不中断整体同步，失败组被记录且不进创建成功列表")
    void shouldContinueWhenOneGroupFails() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "a@example.com"),
                member("GroupB_SUP", "b@example.com"),
                member("GroupC_SUP", "c@example.com")));
        Mockito.when(doucClient.addGroup("GroupA_SUP", "1")).thenReturn(true);
        Mockito.when(doucClient.addGroup("GroupB_SUP", "1"))
                .thenThrow(new IntegrationException("模拟网关失败"));
        Mockito.when(doucClient.addGroup("GroupC_SUP", "1")).thenReturn(true);
        // 首次调用读现有组（空），建完 A、C 后回查组树补齐 ID
        Mockito.when(doucClient.listGroupNameToId())
                .thenReturn(Map.of())
                .thenReturn(Map.of("GroupA_SUP", 11L, "GroupC_SUP", 13L));

        GroupSyncResult result = service.sync(false, false, false);

        Assertions.assertThat(result.getCreatedGroups()).containsExactly("GroupA_SUP", "GroupC_SUP");
        Assertions.assertThat(result.getFailedGroups()).hasSize(1);
        Assertions.assertThat(result.getFailedGroups().get(0)).contains("GroupB_SUP").contains("模拟网关失败");
    }

    @Test
    @DisplayName("parent-group-id 留空时建组传 null，即平铺到顶层")
    void shouldCreateFlatGroupsWhenParentNotConfigured() {
        doucProperties.setParentGroupId("");
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("FlatGroup_SUP", "a@example.com")));
        Mockito.when(doucClient.listGroupNameToId())
                .thenReturn(Map.of())
                .thenReturn(Map.of("FlatGroup_SUP", 20L));

        service.sync(false, false, false);

        // null 表示不挂父组，服务端按 FIRST_LEVEL 建顶层组
        Mockito.verify(doucClient).addGroup("FlatGroup_SUP", null);
    }

    @Test
    @DisplayName("parent-group-id 只有空白字符时同样按平铺处理")
    void shouldTreatBlankParentAsFlat() {
        doucProperties.setParentGroupId("   ");
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("FlatGroup_SUP", "a@example.com")));
        Mockito.when(doucClient.listGroupNameToId())
                .thenReturn(Map.of())
                .thenReturn(Map.of("FlatGroup_SUP", 20L));

        service.sync(false, false, false);

        Mockito.verify(doucClient).addGroup("FlatGroup_SUP", null);
    }

    @Test
    @DisplayName("建组后回查组树能补上新组 ID")
    void shouldResolveCreatedGroupIds() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("NewGroup_SUP", "a@example.com")));
        Mockito.when(doucClient.listGroupNameToId())
                .thenReturn(Map.of())
                .thenReturn(Map.of("NewGroup_SUP", 42L));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of());
        Mockito.when(doucClient.listGroupMembers(42L)).thenReturn(List.of());

        service.sync(false, true, true);

        Mockito.verify(doucClient).setGroupMembers(ArgumentMatchers.eq(42L), ArgumentMatchers.anyList());
    }

    // ------------------------------------------------------------------
    // 需求 2：按用户组分类用户
    // ------------------------------------------------------------------

    @Test
    @DisplayName("按邮箱把 SN 组员解析成 DOUC 用户 ID，并写入该组")
    void shouldResolveEmailsToUserIdsAndAssign() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "Alice@Example.com"),
                member("GroupA_SUP", "bob@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 7L));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(
                user(101L, "alice@example.com"),
                user(102L, "bob@example.com")));
        Mockito.when(doucClient.listGroupMembers(7L)).thenReturn(List.of());

        GroupSyncResult result = service.sync(false, true, true);

        Assertions.assertThat(result.getMatchedUserCount()).isEqualTo(2);
        Assertions.assertThat(result.getUnmatchedUserCount()).isZero();

        GroupMemberPlan plan = planOf(result, "GroupA_SUP");
        Assertions.assertThat(plan.getGroupId()).isEqualTo(7L);
        Assertions.assertThat(plan.getTargetUserIds()).containsExactlyInAnyOrder(101L, 102L);
        Assertions.assertThat(plan.getToAdd()).containsExactlyInAnyOrder(101L, 102L);
        Assertions.assertThat(plan.isApplied()).isTrue();

        Mockito.verify(doucClient).setGroupMembers(7L, List.of(101L, 102L));
    }

    @Test
    @DisplayName("邮箱匹配不上时回退用 user_name 匹配")
    void shouldFallBackToUserNameWhenEmailMisses() {
        // SN 记录的邮箱在 DOUC 里没有，但 user_name 能对上 userAlias
        SnGroupMember snMember = new SnGroupMember();
        snMember.setGroup("GroupA_SUP");
        snMember.setUserEmail("albertli@squareuat.com.hk");
        snMember.setUserName("AlbertLi");

        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(snMember));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 7L));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "albertli")));
        Mockito.when(doucClient.listGroupMembers(7L)).thenReturn(List.of());

        GroupSyncResult result = service.sync(false, true, true);

        Assertions.assertThat(result.getMatchedUserCount()).isEqualTo(1);
        Assertions.assertThat(planOf(result, "GroupA_SUP").getTargetUserIds()).containsExactly(101L);
        Mockito.verify(doucClient).setGroupMembers(7L, List.of(101L));
    }

    @Test
    @DisplayName("邮箱优先：两个键都能匹配上时取邮箱那个用户")
    void shouldPreferEmailOverUserName() {
        SnGroupMember snMember = new SnGroupMember();
        snMember.setGroup("GroupA_SUP");
        snMember.setUserEmail("shared@example.com");
        snMember.setUserName("shared-alias");

        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(snMember));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 7L));
        // 两个键分别指向不同用户，邮箱那个应胜出
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(
                user(101L, "shared@example.com"),
                user(202L, "shared-alias")));
        Mockito.when(doucClient.listGroupMembers(7L)).thenReturn(List.of());

        GroupSyncResult result = service.sync(false, true, true);

        Assertions.assertThat(planOf(result, "GroupA_SUP").getTargetUserIds()).containsExactly(101L);
    }

    @Test
    @DisplayName("同一成员的两个标识指向同一用户时只写入一次，不产生重复")
    void shouldNotDuplicateWhenBothKeysHitSameUser() {
        SnGroupMember snMember = new SnGroupMember();
        snMember.setGroup("GroupA_SUP");
        snMember.setUserEmail("a@example.com");
        snMember.setUserName("a");

        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(snMember));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 7L));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "a@example.com")));
        Mockito.when(doucClient.listGroupMembers(7L)).thenReturn(List.of());

        GroupSyncResult result = service.sync(false, true, true);

        Assertions.assertThat(planOf(result, "GroupA_SUP").getTargetUserIds()).containsExactly(101L);
        Assertions.assertThat(result.getSourceUserCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("匹配不到的邮箱被记录，不阻塞该组其他成员写入")
    void shouldRecordUnmatchedEmailsWithoutBlockingOthers() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "known@example.com"),
                member("GroupA_SUP", "ghost@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 7L));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "known@example.com")));
        Mockito.when(doucClient.listGroupMembers(7L)).thenReturn(List.of());

        GroupSyncResult result = service.sync(false, true, true);

        Assertions.assertThat(result.getMatchedUserCount()).isEqualTo(1);
        Assertions.assertThat(result.getUnmatchedUserCount()).isEqualTo(1);
        Assertions.assertThat(result.getUnmatchedMemberSamples()).containsExactly("ghost@example.com");

        GroupMemberPlan plan = planOf(result, "GroupA_SUP");
        Assertions.assertThat(plan.getResolvedMembers()).containsExactly("known@example.com");
        Assertions.assertThat(plan.getUnmatchedMembers()).containsExactly("ghost@example.com");
        Assertions.assertThat(plan.getTargetUserIds()).containsExactly(101L);
    }

    @Test
    @DisplayName("全量覆盖语义：DOUC 中多出来的成员会被计入待移除")
    void shouldComputeMembersToRemove() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "alice@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 7L));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "alice@example.com")));
        // DOUC 现有 [alice, carol, dave]
        Mockito.when(doucClient.listGroupMembers(7L)).thenReturn(List.of(
                groupMember(101L), groupMember(999L), groupMember(1000L)));

        GroupSyncResult result = service.sync(false, true, true);

        GroupMemberPlan plan = planOf(result, "GroupA_SUP");
        Assertions.assertThat(plan.getExistingMemberCount()).isEqualTo(3);
        Assertions.assertThat(plan.getTargetUserIds()).containsExactly(101L);
        Assertions.assertThat(plan.getToRemove()).containsExactlyInAnyOrder(999L, 1000L);
        Assertions.assertThat(plan.getToAdd()).isEmpty();

        Mockito.verify(doucClient).setGroupMembers(7L, List.of(101L));
    }

    @Test
    @DisplayName("dryRun 下成员归类只出计划，不调用写入接口")
    void shouldNotWriteMembersWhenDryRun() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "alice@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 7L));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "alice@example.com")));
        Mockito.when(doucClient.listGroupMembers(7L)).thenReturn(List.of());

        GroupSyncResult result = service.sync(true, true, true);

        GroupMemberPlan plan = planOf(result, "GroupA_SUP");
        Assertions.assertThat(plan.isApplied()).isFalse();
        Assertions.assertThat(plan.getSkippedReason()).contains("dryRun");
        Mockito.verify(doucClient, Mockito.never()).setGroupMembers(ArgumentMatchers.anyLong(), ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("组未建成时跳过成员归类，并说明原因")
    void shouldSkipMemberSyncWhenGroupMissing() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("Broken_SUP", "a@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of());
        Mockito.when(doucClient.addGroup("Broken_SUP", "1"))
                .thenThrow(new IntegrationException("模拟建组失败"));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "a@example.com")));

        GroupSyncResult result = service.sync(false, true, true);

        GroupMemberPlan plan = planOf(result, "Broken_SUP");
        Assertions.assertThat(plan.getGroupId()).isNull();
        Assertions.assertThat(plan.isApplied()).isFalse();
        Assertions.assertThat(plan.getSkippedReason()).contains("does not exist");
        Mockito.verify(doucClient, Mockito.never()).setGroupMembers(ArgumentMatchers.anyLong(), ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("dryRun 下尚未建的组也要算出成员计划，否则 dryRun 失去意义")
    void shouldStillPlanMembersForNotYetCreatedGroupsInDryRun() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("NewGroup_SUP", "alice@example.com"),
                member("NewGroup_SUP", "ghost@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of());
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "alice@example.com")));

        GroupSyncResult result = service.sync(true, true, true);

        GroupMemberPlan plan = planOf(result, "NewGroup_SUP");
        // 组还没建，ID 应为空，跳过原因要说明是 dryRun 而非建组失败
        Assertions.assertThat(plan.getGroupId()).isNull();
        Assertions.assertThat(plan.getSkippedReason()).contains("dryRun");
        // 但成员解析结果必须已经算出来
        Assertions.assertThat(plan.getTargetUserIds()).containsExactly(101L);
        Assertions.assertThat(plan.getResolvedMembers()).containsExactly("alice@example.com");
        Assertions.assertThat(plan.getUnmatchedMembers()).containsExactly("ghost@example.com");
        Mockito.verify(doucClient, Mockito.never()).setGroupMembers(ArgumentMatchers.anyLong(), ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("实跑时建组失败，跳过原因应是组未建立而非 dryRun")
    void shouldDistinguishFailedGroupFromDryRunSkip() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("Broken_SUP", "a@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of());
        Mockito.when(doucClient.addGroup("Broken_SUP", "1"))
                .thenThrow(new IntegrationException("模拟建组失败"));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "a@example.com")));

        GroupSyncResult result = service.sync(false, true, true);

        Assertions.assertThat(planOf(result, "Broken_SUP").getSkippedReason()).contains("does not exist");
    }

    @Test
    @DisplayName("没有该组现有成员读取时也能正常写入")
    void shouldHandleEmptyExistingMembers() {
        Mockito.when(serviceNowClient.fetchGroupMembers()).thenReturn(List.of(
                member("GroupA_SUP", "alice@example.com")));
        Mockito.when(doucClient.listGroupNameToId()).thenReturn(Map.of("GroupA_SUP", 7L));
        Mockito.when(doucClient.listAllUsers()).thenReturn(List.of(user(101L, "alice@example.com")));
        Mockito.when(doucClient.listGroupMembers(7L)).thenReturn(List.of());

        GroupSyncResult result = service.sync(false, true, true);

        Assertions.assertThat(planOf(result, "GroupA_SUP").getExistingMemberCount()).isZero();
    }

    @Test
    @DisplayName("成员明细日志逐组列出成员标识，未匹配的标 UNMATCHED")
    void describeGroupMembersListsKeysAndMarksUnmatched() {
        GroupMemberPlan plan = new GroupMemberPlan();
        plan.setGroupName("GroupA_SUP");
        plan.setGroupId(7L);
        plan.getSourceMembers().addAll(List.of("alice@example.com", "bob@example.com"));
        plan.getResolvedMembers().add("alice@example.com");
        plan.getUnmatchedMembers().add("bob@example.com");
        plan.setExistingMemberCount(1);
        plan.getToAdd().add(101L);
        plan.setApplied(true);

        Assertions.assertThat(GroupSyncServiceImpl.describeGroupMembers(plan, 100))
                .contains("name=GroupA_SUP", "groupId=7", "source=2", "resolved=1", "unmatched=1")
                .contains("existing=1", "toAdd=1", "toRemove=0", "applied=true")
                .contains("alice@example.com", "bob@example.com (UNMATCHED)")
                .doesNotContain("truncated");
    }

    @Test
    @DisplayName("成员过多时按上限截断，并在行尾明确标记")
    void describeGroupMembersMarksTruncation() {
        GroupMemberPlan plan = new GroupMemberPlan();
        plan.setGroupName("Big_SUP");
        plan.getSourceMembers().addAll(List.of("a@example.com", "b@example.com", "c@example.com"));

        Assertions.assertThat(GroupSyncServiceImpl.describeGroupMembers(plan, 1))
                .contains("members=[a@example.com]", "truncated=true", "sourceTotal=3")
                .doesNotContain("b@example.com");
    }

    @Test
    @DisplayName("未写入的组在明细里带上跳过原因")
    void describeGroupMembersIncludesSkipReason() {
        GroupMemberPlan plan = new GroupMemberPlan();
        plan.setGroupName("Dry_SUP");
        plan.getSourceMembers().add("a@example.com");
        plan.setSkippedReason("dryRun, nothing written");

        Assertions.assertThat(GroupSyncServiceImpl.describeGroupMembers(plan, 100))
                .contains("applied=false", "skipped=\"dryRun, nothing written\"");
    }
}
