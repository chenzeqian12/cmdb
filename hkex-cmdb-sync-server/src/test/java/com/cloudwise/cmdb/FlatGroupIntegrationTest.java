package com.cloudwise.cmdb;

import com.cloudwise.cmdb.integration.douc.DoucClient;
import com.cloudwise.cmdb.integration.douc.dto.DoucGroup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;


/**
 * 验证「平铺建组」这条路径确实端到端可用。
 *
 * <pre>
 * mvn test -Dtest=FlatGroupIntegrationTest -Dit.enabled=true
 * </pre>
 *
 * <p>单元测试只能验证 {@code addGroup(name, null)} 被调用；raw curl 只能验证服务端认得 null。
 * 这里补的是中间那一段——<b>Jackson 序列化时 null 会不会被 {@code @JsonInclude} 吃掉，
 * 以及建出来的组到底在不在顶层</b>。建完即删，可重复执行。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "it.enabled", matches = "true")
class FlatGroupIntegrationTest {

    private static final String PROBE_NAME = "zz-flat-probe-delete-me";

    @Autowired
    private DoucClient doucClient;

    @Test
    @DisplayName("parentId 传 null 建出来的组在顶层，而不是挂在某个父组下")
    void shouldCreateTopLevelGroupWhenParentIsNull() {
        // ---------- Arrange：确保探针组不存在 ----------
        Long stale = doucClient.listGroupNameToId().get(PROBE_NAME);
        if (stale != null) {
            doucClient.deleteGroup(stale);
        }

        Long newId = null;
        try {
            // ---------- Act ----------
            doucClient.addGroup(PROBE_NAME, null);

            // ---------- Assert ----------
            Optional<DoucGroup> topLevel = doucClient.listGroups().stream()
                    .filter(g -> PROBE_NAME.equals(g.getName()))
                    .findFirst();

            Assertions.assertThat(topLevel)
                    .as("传 null 的组应直接出现在顶层，而不是作为某个组的子组")
                    .isPresent();

            newId = topLevel.get().getId();
            log.info("平铺建组成功：id={}，位于顶层，同级共 {} 个顶层组",
                    newId, doucClient.listGroups().size());

            // 顺带确认它没被塞进任何别的组的子孙里（逐层递归，不能只查一层）
            List<DoucGroup> descendants = new ArrayList<>();
            doucClient.listGroups().forEach(root -> collectDescendants(root, descendants));
            Assertions.assertThat(descendants)
                    .as("平铺的组不应出现在任何其他组的子孙里")
                    .extracting(DoucGroup::getId)
                    .doesNotContain(newId);

        } finally {
            // ---------- Cleanup ----------
            if (newId != null) {
                doucClient.deleteGroup(newId);
                log.info("已清理探针组 id={}", newId);
            } else {
                Long created = doucClient.listGroupNameToId().get(PROBE_NAME);
                if (created != null) {
                    doucClient.deleteGroup(created);
                    log.info("已清理探针组 id={}", created);
                }
            }
        }
    }

    /** 递归收集子孙节点。 */
    private void collectDescendants(DoucGroup group, List<DoucGroup> sink) {
        for (DoucGroup child : group.getChildren()) {
            sink.add(child);
            collectDescendants(child, sink);
        }
    }
}
