package com.cloudwise.cmdb.integration.servicenow.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * ServiceNow Table API 的统一响应信封。
 *
 * <p>形如 {@code {"result":[...]}}，出错时 {@code result} 缺省而返回
 * {@code {"error":{...}}}，此时由调用方按 HTTP 状态码抛异常，不在此建模。</p>
 *
 * @param <T> 记录类型
 * @author hkex-cmdb-sync
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnTableResponse<T> {

    /** 记录列表。 */
    private List<T> result;
}
