package com.cloudwise.cmdb.controller;

import com.cloudwise.cmdb.integration.douc.DoucClient;
import com.cloudwise.cmdb.integration.douc.dto.DoucGroup;
import com.cloudwise.cmdb.common.api.ApiResponse;
import com.cloudwise.cmdb.pojo.dto.GroupSyncResult;
import com.cloudwise.cmdb.service.GroupSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 同步触发接口。
 *
 * <p>供联调与运维手工触发使用；后续接入调度时直接复用 {@link GroupSyncService}。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@RestController
@RequestMapping("/sync")
public class SyncController {

    private final GroupSyncService groupSyncService;

    private final DoucClient doucClient;

    public SyncController(GroupSyncService groupSyncService, DoucClient doucClient) {
        this.groupSyncService = groupSyncService;
        this.doucClient = doucClient;
    }

    /**
     * 触发用户组同步（需求 1 + 需求 2）。
     *
     * @param dryRun  是否试运行。缺省取配置 douc.dry-run，配置默认 true，即不写数据。
     * @param members 是否执行「按用户组分类用户」，默认 true
     * @return 同步结果，含每组的成员增删计划
     */
    @PostMapping("/groups")
    public ApiResponse<GroupSyncResult> syncGroups(
            @RequestParam(value = "dryRun", required = false) Boolean dryRun,
            @RequestParam(value = "members", defaultValue = "true") boolean members) {
        try {
            return ApiResponse.ok(groupSyncService.sync(dryRun, members, members));
        } catch (Exception e) {
            log.error("用户组同步失败", e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * 只验证匹配率：不建组、不动成员，只回答「SN 的组员邮箱能在 DOUC 里匹配上多少」。
     *
     * @return 同步结果，memberPlans 为空
     */
    @PostMapping("/groups/match-check")
    public ApiResponse<GroupSyncResult> matchCheck() {
        try {
            return ApiResponse.ok(groupSyncService.sync(true, false, true));
        } catch (Exception e) {
            log.error("匹配率校验失败", e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * 只读查看 DOUC 现有组树的组名与 ID。
     *
     * @return 组名到组 ID 的映射
     */
    @GetMapping("/groups/remote")
    public ApiResponse<Map<String, Long>> remoteGroups() {
        try {
            return ApiResponse.ok(doucClient.listGroupNameToId());
        } catch (Exception e) {
            log.error("查询 DOUC 组树失败", e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * 只读查看 DOUC 原始组树，保留层级。
     *
     * @return 根节点列表
     */
    @GetMapping("/groups/remote/tree")
    public ApiResponse<List<DoucGroup>> remoteGroupTree() {
        try {
            return ApiResponse.ok(doucClient.listGroups());
        } catch (Exception e) {
            log.error("查询 DOUC 组树失败", e);
            return ApiResponse.fail(e.getMessage());
        }
    }
}
