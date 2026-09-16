package com.cloudwise.cmdb.integration.douc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * DOUC 用户分页结果。
 *
 * <p>{@code /api/v2/user/list} 的 data 字段结构，实测形如：</p>
 *
 * <pre>
 * {"current":1,"size":20,"total":141,"pages":8,"records":[...]}
 * </pre>
 *
 * <p>{@code size} 是服务端的每页上限（固定 20，与请求里传的值无关），
 * {@code pages} 是总页数。翻页依据取 {@code pages}。</p>
 *
 * @author hkex-cmdb-sync
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoucUserPage {

    /** 当前页码，从 1 开始。 */
    private Integer current;

    /** 每页条数，服务端固定返回 20。 */
    private Integer size;

    /** 总条数。 */
    private Long total;

    /** 总页数。翻页以它为准。 */
    private Integer pages;

    /** 当前页记录。 */
    private List<DoucUser> records = new ArrayList<>();
}
