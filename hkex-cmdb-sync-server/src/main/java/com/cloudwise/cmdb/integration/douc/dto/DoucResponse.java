package com.cloudwise.cmdb.integration.douc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.cloudwise.cmdb.constant.DoucConstants;
import lombok.Data;

/**
 * DOUC 网关接口的统一响应信封。
 *
 * <p>实测所有接口都返回 {@code {"status":"success","code":100000,"msg":null,"data":...}}，
 * 成功码恒为 {@link DoucConstants#SUCCESS_CODE}。</p>
 *
 * @param <T> 业务数据类型
 * @author hkex-cmdb-sync
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoucResponse<T> {

    /** 状态字符串，成功为 success。 */
    private String status;

    /** 业务状态码，成功为 100000。 */
    private Integer code;

    /** 失败时的提示信息。 */
    private String msg;

    /** 业务数据。 */
    private T data;

    /**
     * 网关附加的布尔结果位。
     *
     * <p>实测与 {@code status} 一致，但为冗余字段，仅作参考，判定一律走
     * {@link #isSuccess()}。</p>
     */
    private Boolean success;

    /**
     * 是否为成功响应。
     *
     * <p>同时校验 {@code status} 与 {@code code}，因为网关在部分异常下会返回
     * HTTP 200 但业务失败。</p>
     *
     * @return true 表示业务成功
     */
    public boolean isSuccess() {
        return DoucConstants.SUCCESS_STATUS.equals(status)
                && code != null
                && code == DoucConstants.SUCCESS_CODE;
    }

    /**
     * 失败时生成带上下文的错误描述。
     *
     * @return 形如 {@code code=xxx, status=yyy, msg=zzz} 的描述
     */
    public String describeFailure() {
        return "code=" + code + ", status=" + status + ", msg=" + msg;
    }
}
