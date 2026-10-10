package com.cloudwise.cmdb.config.remote;

import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.config.ServiceNowProperties;
import java.util.Map;

/** 解密并校验后的 CMDB 接入配置快照。 */
public record CmdbRemoteConfigSnapshot(
        ServiceNowProperties serviceNow,
        DoucProperties douc,
        Map<String, Object> properties) {
}
