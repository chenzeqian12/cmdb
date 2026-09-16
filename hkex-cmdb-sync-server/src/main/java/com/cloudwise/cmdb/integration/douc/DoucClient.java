package com.cloudwise.cmdb.integration.douc;

import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.constant.DoucConstants;
import com.cloudwise.cmdb.common.exception.IntegrationException;
import com.cloudwise.cmdb.integration.douc.dto.DoucGroup;
import com.cloudwise.cmdb.integration.douc.dto.DoucGroupMember;
import com.cloudwise.cmdb.integration.douc.dto.DoucResponse;
import com.cloudwise.cmdb.integration.douc.dto.DoucUser;
import com.cloudwise.cmdb.integration.douc.dto.DoucUserPage;
import com.cloudwise.cmdb.integration.douc.dto.GroupAddRequest;
import com.cloudwise.cmdb.integration.douc.dto.GroupMemberSetRequest;
import com.cloudwise.cmdb.integration.douc.dto.UserListRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DOUC（用户中心）客户端。
 *
 * <p>走网关 {@code /docp/gateway/douc/api/v1/**}，覆盖组树查询、建组、用户分页查询。</p>
 *
 * <p><b>两个必须注意的实测行为：</b></p>
 * <ol>
 *   <li>网关对业务错误一律返回 HTTP 200，失败信息在响应体的 {@code status/code/msg} 里。
 *       因此不能依赖 HTTP 状态码判断成败，必须走 {@link DoucResponse#isSuccess()}。</li>
 *   <li>建组成功时 {@code data} 为 {@code null}，拿不到新组 ID。
 *       需要新组 ID 时必须回查 {@link #listGroups()}。</li>
 * </ol>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
public class DoucClient {

    /** 同级组名重复时网关返回的业务码。 */
    public static final int CODE_GROUP_NAME_DUPLICATED = 110503;

    /** 服务端每页固定返回的条数上限，请求里传更大也无效。 */
    private static final int DOUC_USER_PAGE_MAX = 20;

    /** 分页保护的页码上限。按每页 20 条算可覆盖 4 万用户，超出会告警而不是静默截断。 */
    private static final int MAX_USER_PAGES = 2000;

    private final RestClient restClient;

    private final DoucProperties properties;

    private final DoucAuthProvider authProvider;

    public DoucClient(RestClient doucRestClient, DoucProperties properties, DoucAuthProvider authProvider) {
        this.restClient = doucRestClient;
        this.properties = properties;
        this.authProvider = authProvider;
    }

    /**
     * 查询全量组树。
     *
     * @return 根节点列表
     */
    public List<DoucGroup> listGroups() {
        DoucResponse<List<DoucGroup>> response = restClient.get()
                .uri(buildUri(DoucConstants.PATH_GROUP_LIST))
                .headers(authProvider::applyAuth)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<DoucResponse<List<DoucGroup>>>() {
                });

        requireSuccess(response, "查询 DOUC 组树");
        return response.getData() == null ? new ArrayList<>() : response.getData();
    }

    /**
     * 把组树摊平成 {@code 组名 -&gt; 组 ID}，用于判重。
     *
     * <p>同名组若出现在不同分支，保留先遍历到的那个并告警，避免静默覆盖。</p>
     *
     * @return 组名到组 ID 的映射
     */
    public Map<String, Long> listGroupNameToId() {
        Map<String, Long> result = new LinkedHashMap<>();
        collectGroups(listGroups(), result);
        return result;
    }

    private void collectGroups(List<DoucGroup> groups, Map<String, Long> sink) {
        for (DoucGroup group : groups) {
            if (StringUtils.hasText(group.getName())) {
                Long previous = sink.putIfAbsent(group.getName(), group.getId());
                if (previous != null) {
                    log.warn("DOUC 中存在同名组，name={}，已保留 id={}，忽略 id={}",
                            group.getName(), previous, group.getId());
                }
            }
            collectGroupChildren(group, sink);
        }
    }

    private void collectGroupChildren(DoucGroup group, Map<String, Long> sink) {
        collectGroups(group.getChildren(), sink);
    }

    /**
     * 新建用户组。
     *
     * @param name     组名
     * @param parentId 父组 ID；<b>传 null 表示平铺到顶层</b>（服务端按 FIRST_LEVEL 处理）
     * @return true 表示创建成功；false 表示同级已有同名组
     */
    public boolean addGroup(String name, String parentId) {
        // leader 传空串：HKEX dev 实测该字段允许为空，组长后续由业务侧在 DOUC 界面指派
        GroupAddRequest request = new GroupAddRequest(properties.getDefaultGroupType(), parentId, "", name);

        DoucResponse<Void> response = restClient.post()
                .uri(buildUri(DoucConstants.PATH_GROUP_ADD))
                .headers(authProvider::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<DoucResponse<Void>>() {
                });

        if (response != null && response.getCode() != null
                && response.getCode() == CODE_GROUP_NAME_DUPLICATED) {
            log.debug("DOUC 同级已存在同名组，跳过创建，name={}", name);
            return false;
        }
        requireSuccess(response, "创建 DOUC 用户组 " + name);
        return true;
    }

    /**
     * 分页拉取全量用户。
     *
     * <p><b>翻页依据是服务端返回的 {@code pages}，不是「本页是否取满」。</b>
     * 服务端每页固定返回 20 条（与请求里的 size 无关），如果按「返回数 &lt; 请求数就结束」
     * 判断，第一页就会终止——2026-09-16 实测踩过这个坑，
     * 表现为 141 个用户只拉到 20 个，最终匹配率恒为 0。</p>
     *
     * <p>{@code pages} 缺失时退化为「本页为空则结束」，并用页码上限兜底防死循环。</p>
     *
     * @return 全量用户列表
     */
    public List<DoucUser> listAllUsers() {
        List<DoucUser> users = new ArrayList<>();
        int current = 1;

        while (true) {
            DoucUserPage page = listUserPage(current, properties.getUserPageSize());
            List<DoucUser> records = page == null || page.getRecords() == null
                    ? new ArrayList<>()
                    : page.getRecords();
            users.addAll(records);

            if (page != null && page.getPages() != null) {
                if (current >= page.getPages()) {
                    break;
                }
            } else if (records.isEmpty()) {
                // 拿不到总页数时，以「本页为空」作为终止条件
                break;
            }

            current++;
            if (current > MAX_USER_PAGES) {
                log.warn("DOUC 用户分页达到上限 {} 页，提前结束，已拉取 {} 条；"
                        + "若用户总数确实超过 {}，需调大上限",
                        MAX_USER_PAGES, users.size(), (long) MAX_USER_PAGES * DOUC_USER_PAGE_MAX);
                break;
            }
        }
        log.info("DOUC 用户拉取完成，共 {} 条", users.size());
        return users;
    }

    /**
     * 分页查询用户（只取记录列表）。
     *
     * @param current 页码，从 1 开始
     * @param size    每页条数。服务端有硬上限 {@value #DOUC_USER_PAGE_MAX}，传大也无效
     * @return 当前页用户
     */
    public List<DoucUser> listUsers(int current, int size) {
        DoucUserPage page = listUserPage(current, size);
        return page == null || page.getRecords() == null ? new ArrayList<>() : page.getRecords();
    }

    /**
     * 分页查询用户，返回含 {@code total} / {@code pages} 的完整分页对象。
     *
     * @param current 页码，从 1 开始
     * @param size    每页条数
     * @return 分页结果
     */
    public DoucUserPage listUserPage(int current, int size) {
        DoucResponse<DoucUserPage> response = restClient.post()
                .uri(buildUri(DoucConstants.PATH_USER_LIST))
                .headers(authProvider::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(new UserListRequest(current, size))
                .retrieve()
                .body(new ParameterizedTypeReference<DoucResponse<DoucUserPage>>() {
                });

        requireSuccess(response, "分页查询 DOUC 用户，current=" + current);
        return response.getData();
    }

    /**
     * 查询组内成员。
     *
     * @param groupId 组 ID
     * @return 成员列表
     */
    public List<DoucGroupMember> listGroupMembers(Long groupId) {
        String path = String.format(DoucConstants.PATH_GROUP_USER_LIST, groupId);
        DoucResponse<List<DoucGroupMember>> response = restClient.get()
                .uri(buildUri(path))
                .headers(authProvider::applyAuth)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<DoucResponse<List<DoucGroupMember>>>() {
                });

        requireSuccess(response, "查询组内成员，groupId=" + groupId);
        return response.getData() == null ? new ArrayList<>() : response.getData();
    }

    /**
     * 设置组内成员。
     *
     * <p><b>这是全量覆盖，不是增量追加。</b>服务端按
     * {@code delUserIds = difference(现有成员, userIdList)} 先删后增，
     * 因此调用方必须传入该组的完整期望成员列表。</p>
     *
     * <p>{@code userIdList} 为 null 会让服务端 NPE（表现为 100001 系统错误），
     * 传空列表则会清空该组全部成员，两者语义都危险，这里显式做前置校验。</p>
     *
     * @param groupId    组 ID
     * @param userIdList 该组的完整成员用户 ID 列表
     */
    public void setGroupMembers(Long groupId, List<Long> userIdList) {
        if (groupId == null) {
            throw new IntegrationException("设置组内成员失败：groupId 不能为空");
        }
        if (userIdList == null) {
            throw new IntegrationException("设置组内成员失败：userIdList 不能为 null，清空成员请传空列表");
        }

        DoucResponse<Void> response = restClient.post()
                .uri(buildUri(DoucConstants.PATH_GROUP_DISTRIBUTE))
                .headers(authProvider::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(new GroupMemberSetRequest(groupId, userIdList))
                .retrieve()
                .body(new ParameterizedTypeReference<DoucResponse<Void>>() {
                });

        requireSuccess(response, "设置组内成员，groupId=" + groupId + "，成员数=" + userIdList.size());
        log.info("已设置 DOUC 组 {} 的成员，共 {} 人", groupId, userIdList.size());
    }

    /**
     * 删除用户组。
     *
     * <p>主要给联调收尾与误建清理用。</p>
     *
     * @param groupId 组 ID
     */
    public void deleteGroup(Long groupId) {
        String path = String.format(DoucConstants.PATH_GROUP_DELETE, groupId);
        DoucResponse<Void> response = restClient.post()
                .uri(buildUri(path))
                .headers(authProvider::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(new ParameterizedTypeReference<DoucResponse<Void>>() {
                });

        requireSuccess(response, "删除 DOUC 用户组，groupId=" + groupId);
        log.info("已删除 DOUC 用户组 id={}", groupId);
    }

    private String buildUri(String path) {
        return properties.getGatewayPrefix() + properties.getApiPrefix() + path;
    }

    private void requireSuccess(DoucResponse<?> response, String action) {
        if (response == null) {
            throw new IntegrationException(action + " 失败：响应为空");
        }
        if (!response.isSuccess()) {
            throw new IntegrationException(action + " 失败：" + response.describeFailure());
        }
    }
}
