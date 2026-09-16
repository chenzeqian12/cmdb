package com.cloudwise.cmdb.integration.douc;

import org.springframework.http.HttpHeaders;

/**
 * DOUC 请求认证注入点。
 *
 * <p>当前只有浏览器会话 Cookie 一种实现，会话过期需人工更换。
 * 后续若拿到应用级正式凭据（appKey / appSecret），新增一个实现类并调整
 * Bean 的生效条件即可，{@link DoucClient} 与业务层无需改动。</p>
 *
 * @author hkex-cmdb-sync
 */
public interface DoucAuthProvider {

    /**
     * 把认证信息写入请求头。
     *
     * @param headers 待发送的请求头，允许就地修改
     */
    void applyAuth(HttpHeaders headers);
}
