package com.cloudwise.cmdb.common.api;

import lombok.Data;

/**
 * 统一接口响应信封。
 *
 * @param <T> 业务数据类型
 * @author hkex-cmdb-sync
 */
@Data
public class ApiResponse<T> {

    /** 是否成功。 */
    private boolean success;

    /** 业务数据，失败时为 null。 */
    private T data;

    /** 失败原因，成功时为 null。 */
    private String error;

    /**
     * 构造成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功信封
     */
    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(true);
        response.setData(data);
        return response;
    }

    /**
     * 构造失败响应。
     *
     * @param error 失败原因
     * @param <T>   数据类型
     * @return 失败信封
     */
    public static <T> ApiResponse<T> fail(String error) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setError(error);
        return response;
    }
}
