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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.Assertions;


/**
 * 在 DOUC dev 环境造出「不同的分组各有用户」的可视结果。
 *
 * <pre>
 * mvn test -Dtest=DevGroupPopulationIntegrationTest -Dit.enabled=true
 * </pre>
 *
 * <p><b>会真实写入 DOUC dev。仅用于演示，不要指向生产。</b></p>
 *
 * <h2>这个测试为什么要用 DOUC 现有用户</h2>
 *
 * <p>正常链路是「SN 组员邮箱 → DOUC 用户」，但 dev 环境里 SN 的用户<strong>一个都不存在</strong>
 * （实测匹配率 0/308，SN 的用户只同步到了生产）。而 DOUC 的建用户接口
 * 走不通：见 README 附录——{@code /user/add} 的明文路径稳定抛裸异常（控制器 catch-all
 * 只能返回无信息的「系统错误」），浏览器用的 AES {@code userInfo} 路径又需要运行时
 * 密钥，仓库里没有。</p>
 *
 * <p>所以这里退一步：<strong>组是真的 SN 组，接口是真的 distribute 接口，
 * 只有成员换成 DOUC dev 里已有的用户</strong>，用来验证「建组 + 按组配人」这套机制
 * 在 dev 上确实能跑通、且结果可见。等 SN 用户在生产可用后，
 * {@link GroupSyncIntegrationTest} 那条链路才是最终形态。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "it.enabled", matches = "true")
class DevGroupPopulationIntegrationTest {

    /** 取多少个 SN 组来演示。 */
    private static final int GROUP_COUNT = 6;

    @Autowired
    private ServiceNowClient serviceNowClient;

    @Autowired
    private DoucClient doucClient;

    @Test
    @DisplayName("用 DOUC 现有用户填满若干 SN 组，造出各组有人的可视结果")
    void shouldPopulateGroupsWithExistingUsers() {
        // ---------- 1. 取真实的 SN 组名 ----------
        List<SnGroupMember> members = serviceNowClient.fetchGroupMembers();
        List<String> snGroups = pickSnGroupNames(members, GROUP_COUNT);
        Assertions.assertThat(snGroups).as("应从 SN 取到源组").hasSize(GROUP_COUNT);
        log.info("选取的 SN 源组：{}", snGroups);

        // ---------- 2. 建组（已存在的跳过） ----------
        Map<String, Long> existing = doucClient.listGroupNameToId();
        for (String name : snGroups) {
            if (!existing.containsKey(name)) {
                doucClient.addGroup(name, "1");
            }
        }
        Map<String, Long> groupIds = doucClient.listGroupNameToId();

        // ---------- 3. 把 DOUC 现有用户切成分片，一组一份 ----------
        List<DoucUser> users = doucClient.listAllUsers();
        Assertions.assertThat(users).as("DOUC 中应有用户可分配").isNotEmpty();

        int shardSize = users.size() / snGroups.size() + 1;
        Map<String, List<Long>> plan = new LinkedHashMap<>();
        for (int i = 0; i < snGroups.size(); i++) {
            int from = i * shardSize;
            int to = Math.min(from + shardSize, users.size());
            if (from >= to) {
                continue;
            }
            List<Long> shard = users.subList(from, to).stream().map(DoucUser::getId).toList();
            plan.put(snGroups.get(i), shard);
        }

        // ---------- 4. 写入成员（全量覆盖） ----------
        Map<String, Integer> written = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : plan.entrySet()) {
            Long groupId = groupIds.get(entry.getKey());
            Assertions.assertThat(groupId).as("组 %s 应已存在于 DOUC", entry.getKey()).isNotNull();

            doucClient.setGroupMembers(groupId, entry.getValue());
            written.put(entry.getKey(), entry.getValue().size());
        }

        // ---------- 5. 回读校验 ----------
        log.info("=== DOUC dev 分组结果 ===");
        int totalAssigned = 0;
        for (Map.Entry<String, List<Long>> entry : plan.entrySet()) {
            Long groupId = groupIds.get(entry.getKey());
            List<DoucGroupMember> actual = doucClient.listGroupMembers(groupId);
            Set<Long> actualIds = new TreeSet<>();
            actual.forEach(m -> actualIds.add(m.getUserId()));

            Assertions.assertThat(actualIds)
                    .as("组 %s 的成员应与写入一致", entry.getKey())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());

            totalAssigned += actualIds.size();
            // SLF4J 只认 {}，不支持 {:<45} 这类填充语法，用 String.format 自己排
            log.info(String.format("组 %-45s id=%-3d 成员 %3d 人，样例：%s",
                    entry.getKey(), groupId, actualIds.size(),
                    actual.stream().limit(5).map(DoucGroupMember::getUserName).toList()));
        }
        log.info("共 {} 个组，累计 {} 条成员关系", plan.size(), totalAssigned);
    }

    /** 按组名排序取前 N 个源组，保证多次运行选到同一批，结果可复现。 */
    private List<String> pickSnGroupNames(List<SnGroupMember> members, int count) {
        Set<String> names = new TreeSet<>();
        for (SnGroupMember member : members) {
            if (member != null && member.isValid()) {
                names.add(member.getGroup().trim());
            }
        }
        return new ArrayList<>(names).subList(0, Math.min(count, names.size()));
    }
}
