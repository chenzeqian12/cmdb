package com.cloudwise.cmdb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * HKEX CMDB 同步服务启动类。
 *
 * <p>负责把 ServiceNow CMDB 的配置项与用户组数据同步到 Cloudwise DOUC / EMP。</p>
 *
 * @author hkex-cmdb-sync
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CmdbSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(CmdbSyncApplication.class, args);
    }
}
