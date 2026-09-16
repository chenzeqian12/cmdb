package com.cloudwise.cmdb.integration.douc.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code /api/v2/user/list} 的请求体。
 *
 * <p><b>字段名必须用 {@code current} / {@code size}，不能用 {@code pageNum} / {@code pageSize}。</b>
 * 服务端 DTO（{@code UserListRequestObject}）的字段就是这两个名字，传错了不会报错，
 * 而是静默忽略、永远返回第 1 页——2026-09-16 实测踩过这个坑，
 * 表现为「用户列表永远只有 20 条，翻页无效，最终匹配率恒为 0」。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserListRequest {

    /** 页码，从 1 开始。 */
    private int current;

    /**
     * 每页条数。
     *
     * <p>服务端有硬上限 20，传再大也只返回 20 条，因此翻页是必需的。</p>
     */
    private int size;
}
