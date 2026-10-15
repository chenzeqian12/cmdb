package com.cloudwise.cmdb.config.remote;

import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.config.ServiceNowProperties;
import java.util.Map;

/** 解密 ServiceNow 登录凭据并校验后的 CMDB 配置快照。 */
public record CmdbRemoteConfigSnapshot(
        ServiceNowProperties serviceNow,
        DoucProperties douc,
        Map<String, Object> properties) {
}
