package com.cloudwise.cmdb.config;

import com.cloudwise.cmdb.common.exception.IntegrationException;
import com.cloudwise.cmdb.service.GroupSyncStateStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文件形式的同步组快照。
 *
 * <p>仅记录本服务实际创建成功的组及其 ID，用于后续安全地识别应删除的组。
 * 文件必须配置在升级后仍会保留的位置。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
public class FileGroupSyncStateStore implements GroupSyncStateStore {

    private static final TypeReference<Map<String, Long>> GROUPS_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    private final Path stateFile;

    private final Path temporaryFile;

    public FileGroupSyncStateStore(ObjectMapper objectMapper,
                                   @Value("${douc.group-sync-state-file:./data/douc-managed-groups.json}")
                                   String stateFilePath) {
        this.objectMapper = objectMapper;
        this.stateFile = Paths.get(stateFilePath).toAbsolutePath().normalize();
        this.temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
    }

    @Override
    public synchronized Map<String, Long> loadManagedGroups() {
        if (!Files.exists(stateFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Long> groups = objectMapper.readValue(stateFile.toFile(), GROUPS_TYPE);
            return groups == null ? new LinkedHashMap<>() : new LinkedHashMap<>(groups);
        } catch (IOException e) {
            throw new IntegrationException("读取 DOUC 同步组快照失败，路径=" + stateFile, e);
        }
    }

    @Override
    public synchronized void saveManagedGroups(Map<String, Long> groups) {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(temporaryFile.toFile(), new LinkedHashMap<>(groups));
            Files.move(temporaryFile, stateFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IntegrationException("保存 DOUC 同步组快照失败，路径=" + stateFile, e);
        }
        log.info("已保存 DOUC 同步组快照，组数={}，路径={}", groups.size(), stateFile);
    }
}
