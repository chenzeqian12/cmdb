package com.cloudwise.cmdb;

import com.cloudwise.cmdb.integration.douc.DoucClient;
import com.cloudwise.cmdb.integration.douc.dto.DoucGroupMember;
import com.cloudwise.cmdb.integration.douc.dto.DoucUser;
import com.cloudwise.cmdb.integration.servicenow.ServiceNowClient;
import com.cloudwise.cmdb.integration.servicenow.dto.SnGroupMember;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;


/**
 * 小样本端到端测试：SN 取组 → DOUC 建组 → 配置组内成员 → 校验 → 清理。
 *
 * <p>只处理 {@link #SAMPLE_SIZE} 个组，跑完会删掉自己建的组，可重复执行。</p>
 *
 * <pre>
 * mvn test -Dtest=SmallSampleSyncIntegrationTest -Dit.enabled=true
 * </pre>
 *
 * <p><b>会真实写入 DOUC。</b>只对 HKEX dev 环境运行。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "it.enabled", matches = "true")
class SmallSampleSyncIntegrationTest {

    /** 小样本规模：只取前 N 个源组。 */
    private static final int SAMPLE_SIZE = 3;

    @Autowired
    private ServiceNowClient serviceNowClient;

    @Autowired
    private DoucClient doucClient;

    @Test
    @DisplayName("小样本全链路：SN 取组 → DOUC 建组 → 配置成员 → 校验 → 清理")
    void shouldSyncSmallSampleEndToEnd() {
        // ---------- Arrange ----------
        List<SnGroupMember> members = serviceNowClient.fetchGroupMembers();
        Map<String, Set<String>> snGroups = groupValidMembersByGroupName(members);
        List<String> sampleGroups = snGroups.keySet().stream().limit(SAMPLE_SIZE).toList();

        Assertions.assertThat(sampleGroups).as("SN 侧应能取到源组").isNotEmpty();
        log.info("小样本源组 {} 个：{}", sampleGroups.size(), sampleGroups);

        // DOUC 现有组，用于判重；本次新建的组 ID 记录下来供清理
        Map<String, Long> existing = doucClient.listGroupNameToId();
        Map<String, Long> created = new LinkedHashMap<>();

        try {
            // ---------- Act 1: 建组 ----------
            for (String name : sampleGroups) {
                if (existing.containsKey(name)) {
                    log.info("组已存在，跳过：{} (id={})", name, existing.get(name));
                    continue;
                }
                doucClient.addGroup(name, "1");
            }
            // 建组接口不返回新组 ID，回查组树拿到
            Map<String, Long> afterCreate = doucClient.listGroupNameToId();
            for (String name : sampleGroups) {
                Long id = afterCreate.get(name);
                if (id != null && !existing.containsKey(name)) {
                    created.put(name, id);
                }
            }
            Assertions.assertThat(created).as("应在 DOUC 中新建出小样本组").hasSizeGreaterThan(0);
            log.info("已建组：{}", created);

            // ---------- Act 2: 配置成员 ----------
            // SN 侧的成员邮箱在 DOUC 中匹配不到（本地验证为 0/308），
            // 因此这里用 DOUC 已有用户演示「配置成员」这一步的完整机制。
            List<DoucUser> doucUsers = doucClient.listUsers(1, 3);
            Assertions.assertThat(doucUsers).as("DOUC 中应有可用于演示的用户").isNotEmpty();
            List<Long> demoUserIds = doucUsers.stream().map(DoucUser::getId).toList();

            Map<String, Long> idToName = new LinkedHashMap<>();
            created.forEach((name, id) -> idToName.put(name, id));

            for (Map.Entry<String, Long> entry : created.entrySet()) {
                doucClient.setGroupMembers(entry.getValue(), demoUserIds);
            }

            // ---------- Assert: 成员确实写进去了 ----------
            for (Map.Entry<String, Long> entry : created.entrySet()) {
                List<DoucGroupMember> groupMembers = doucClient.listGroupMembers(entry.getValue());
                Set<Long> actualIds = new LinkedHashSet<>();
                groupMembers.forEach(m -> actualIds.add(m.getUserId()));
                log.info("组 {} (id={}) 现有成员：{}", entry.getKey(), entry.getValue(),
                        groupMembers.stream().map(DoucGroupMember::getUserName).toList());
                Assertions.assertThat(actualIds)
                        .as("组 %s 的成员应与写入的一致", entry.getKey())
                        .containsExactlyInAnyOrderElementsOf(demoUserIds);
            }

            // ---------- Assert: 全量覆盖语义 ----------
            // 只保留第一个成员，验证多余成员会被移除（服务端是覆盖而非追加）
            Long firstGroupId = created.values().iterator().next();
            List<Long> narrowed = List.of(demoUserIds.get(0));
            doucClient.setGroupMembers(firstGroupId, narrowed);
            Set<Long> afterNarrow = new LinkedHashSet<>();
            doucClient.listGroupMembers(firstGroupId).forEach(m -> afterNarrow.add(m.getUserId()));
            Assertions.assertThat(afterNarrow)
                    .as("再次写入应全量覆盖，只保留传入的成员")
                    .containsExactlyElementsOf(narrowed);
            log.info("已验证：/group/distribute 为全量覆盖语义，缩小列表后多余成员被移除");

        } finally {
            // ---------- Cleanup ----------
            for (Map.Entry<String, Long> entry : created.entrySet()) {
                try {
                    doucClient.deleteGroup(entry.getValue());
                    log.info("已清理测试组：{} (id={})", entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    log.warn("清理测试组失败，需手工删除：{} (id={})", entry.getKey(), entry.getValue(), e);
                }
            }
        }
    }

    /**
     * 按组名归类有效组员，与 {@code GroupSyncServiceImpl} 中的清洗规则保持一致。
     */
    private Map<String, Set<String>> groupValidMembersByGroupName(List<SnGroupMember> members) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (SnGroupMember member : members) {
            if (member == null || !member.isValid()) {
                continue;
            }
            grouped.computeIfAbsent(member.getGroup().trim(), key -> new LinkedHashSet<>())
                    .add(member.getUserEmail().trim().toLowerCase());
        }
        return new LinkedHashMap<>(grouped);
    }
}
