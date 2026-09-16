package com.cloudwise.cmdb.constant;

/**
 * DOUC 接口相关的固定常量。
 *
 * <p>取值均来自 HKEX dev 环境实测，不要凭字面猜测修改。</p>
 *
 * @author hkex-cmdb-sync
 */
public final class DoucConstants {

    /** 成功响应的 status 字段值。 */
    public static final String SUCCESS_STATUS = "success";

    /** 成功响应的 code 字段值。 */
    public static final int SUCCESS_CODE = 100000;

    /** 查询组树的路径。GET，只读。 */
    public static final String PATH_GROUP_LIST = "/group/list";

    /** 新建组的路径。POST。 */
    public static final String PATH_GROUP_ADD = "/group/add";

    /**
     * 分页查询用户的路径。POST。
     *
     * <p>v1 与 v2 都可用（v2 是管理界面「配置用户」调的版本，两者行为一致）。
     * <b>关键是入参字段名必须是 {@code current} / {@code size}</b>，
     * 传 {@code pageNum} / {@code pageSize} 会被静默忽略、永远返回第 1 页，
     * 表现为「用户列表永远只有 20 条」，进而让匹配率恒为 0。</p>
     *
     * <p>另外这个接口<b>按调用人（{@code userId} 头）的可见范围过滤</b>：
     * 实测 userId=1 能看到全部 141 个用户，userId=3 返回 0 条。</p>
     */
    public static final String PATH_USER_LIST = "/user/list";

    /**
     * 配置组内成员的路径。POST，入参 {@code {"id":组ID,"userIdList":[用户ID...]}}。
     *
     * <p><b>全量覆盖语义</b>：不在 userIdList 中的现有成员会被移除。
     * 详见 {@code GroupServiceImpl.addUserListInGroup} 中
     * {@code Sets.difference(oldUserIds, userIdSet)} 的实现。</p>
     */
    public static final String PATH_GROUP_DISTRIBUTE = "/group/distribute";

    /** 查询组内成员的路径。GET，{@code /group/getUserList/{groupId}}。 */
    public static final String PATH_GROUP_USER_LIST = "/group/getUserList/%d";

    /** 删除组的路径。POST，{@code /group/delete/{groupId}}。 */
    public static final String PATH_GROUP_DELETE = "/group/delete/%d";

    /** 组不存在时返回的业务码。 */
    public static final int CODE_GROUP_NOT_FOUND = 110506;

    private DoucConstants() {
    }
}
