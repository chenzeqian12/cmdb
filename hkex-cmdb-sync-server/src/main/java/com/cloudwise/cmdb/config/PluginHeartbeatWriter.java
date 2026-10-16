package com.cloudwise.cmdb.config;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OmniAgent 插件心跳。
 *
 * <p>插件规范要求：插件每 30 秒往「版本根目录」写一个 10 位秒级时间戳到 {@code heartbeat}
 * 文件，底座通过读这个文件判断插件健康状态；同时底座还会通过 {@code agentctl info}
 * 返回的配置路径，把服务端下发的配置写进来。</p>
 *
 * <p>写入用「临时文件 + 原子改名」，避免底座恰好在半写状态下读到空文件而误判插件掉线。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hkex.heartbeat", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PluginHeartbeatWriter {

    /** 统一日志关键字，便于在日志平台按项目检索。 */
    private static final String TAG = "[HKEX-CMDB-SYNC]";

    private final Path heartbeatFile;

    private final Path tempFile;

    public PluginHeartbeatWriter(@Value("${hkex.heartbeat.file:./heartbeat}") String heartbeatPath) {
        this.heartbeatFile = Paths.get(heartbeatPath).toAbsolutePath().normalize();
        this.tempFile = this.heartbeatFile.resolveSibling(this.heartbeatFile.getFileName() + ".tmp");
    }

    /**
     * 心跳写入。启动即写一次，之后按 {@code hkex.heartbeat.interval-seconds} 定频刷新。
     */
    @Scheduled(
            initialDelay = 0,
            fixedDelayString = "${hkex.heartbeat.interval-seconds:30}000")
    public void beat() {
        String stamp = String.valueOf(Instant.now().getEpochSecond());
        try {
            Files.createDirectories(heartbeatFile.getParent());
            Files.writeString(tempFile, stamp + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(tempFile, heartbeatFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 心跳写不出去不致命（服务仍在正常工作），但要让运维在日志里看得到。
            log.error("{}failed to write plugin heartbeat, path={}; the platform may mark the plugin unhealthy",
                    TAG, heartbeatFile, e);
        }
    }

    /**
     * 退出时清掉临时文件，正常退出不删 heartbeat —— 底座靠时间戳变旧来判断掉线。
     */
    @PreDestroy
    public void cleanup() {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("{}failed to delete the heartbeat temp file, path={}", TAG, tempFile, e);
        }
    }
}
