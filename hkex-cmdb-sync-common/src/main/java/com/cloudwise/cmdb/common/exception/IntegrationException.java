package com.cloudwise.cmdb.common.exception;

/**
 * 外部系统集成失败异常。
 *
 * <p>统一包装 ServiceNow / DOUC 调用过程中的协议错误、业务错误码与网络异常，
 * 便于上层按同一类型处理并输出可读日志。</p>
 *
 * @author hkex-cmdb-sync
 */
public class IntegrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IntegrationException(String message) {
        super(message);
    }

    public IntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
