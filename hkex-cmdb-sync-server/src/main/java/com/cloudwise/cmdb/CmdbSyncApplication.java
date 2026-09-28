package com.cloudwise.cmdb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HKEX CMDB 同步服务启动类。
 *
 * <p>负责把 ServiceNow CMDB 的配置项与用户组数据同步到 Cloudwise DOUC / EMP。</p>
 *
 * <p>{@code @EnableScheduling} 是给 OmniAgent 插件心跳用的（见 {@link com.cloudwise.cmdb.config.PluginHeartbeatWriter}），
 * 底座按版本根目录下的 heartbeat 文件判断插件健康状态。</p>
 *
 * @author hkex-cmdb-sync
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CmdbSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(CmdbSyncApplication.class, args);
    }
}
