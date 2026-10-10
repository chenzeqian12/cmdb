package com.cloudwise.cmdb.config.remote;

import org.springframework.core.env.Environment;

/** CMDB 远程密文配置的位置。 */
public record CmdbRemoteConfigSettings(boolean enabled, String group, String dataId, long timeoutMs) {

    private static final String PREFIX = "cmdb.remote-config.";

    public static CmdbRemoteConfigSettings defaults() {
        return new CmdbRemoteConfigSettings(
                true, "hkex-cmdb-sync", "hkex-cmdb-sync-config.json", 3000L);
    }

    public static CmdbRemoteConfigSettings from(Environment environment) {
        CmdbRemoteConfigSettings defaults = defaults();
        return new CmdbRemoteConfigSettings(
                environment.getProperty(PREFIX + "enabled", Boolean.class, defaults.enabled()),
                environment.getProperty(PREFIX + "group", defaults.group()),
                environment.getProperty(PREFIX + "data-id", defaults.dataId()),
                environment.getProperty(PREFIX + "timeout-ms", Long.class, defaults.timeoutMs()));
    }

    public void validate() {
        if (group == null || group.isBlank() || dataId == null || dataId.isBlank() || timeoutMs < 1) {
            throw new IllegalStateException("CMDB Nacos configuration location is incomplete");
        }
    }
}
