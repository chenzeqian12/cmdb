package com.cloudwise.cmdb.integration.douc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * DOUC 用户组节点。
 *
 * <p>{@code /group/list} 返回的是一棵树，节点自带 {@code children}。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoucGroup {

    /** 组 ID，全局唯一。 */
    private Long id;

    /** 组名，同步时以此判重。 */
    private String name;

    /** 组类型，建组时必传。 */
    private Integer type;

    /** 同级排序值。 */
    private Integer sequence;

    /** 组描述。 */
    private String description;

    /**
     * 子组。
     *
     * <p>实测叶子节点返回的是 JSON {@code null} 而非空数组，Jackson 会把默认的空列表
     * 覆盖成 null，因此遍历前必须做空判断，见 {@link #getChildren()}。</p>
     */
    private List<DoucGroup> children;

    /**
     * 安全获取子组，永不返回 null。
     *
     * @return 子组列表，无子组时为空列表
     */
    public List<DoucGroup> getChildren() {
        return children == null ? new ArrayList<>() : children;
    }
}
