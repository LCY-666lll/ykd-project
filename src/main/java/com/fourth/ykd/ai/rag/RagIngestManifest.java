package com.fourth.ykd.ai.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 摄入清单管理。
 *
 * <p>维护 JSON 文件记录每个已摄入文档的 SHA-256 哈希值和 chunk 数，
 * 用于判断文档是否已变更、是否需要重新嵌入。
 */
@Slf4j
@Component
public class RagIngestManifest {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, FileEntry> fileEntries = new ConcurrentHashMap<>();

    private volatile int totalChunks = 0;

    private final Path manifestPath;

    private final int configVersion;

    private record FileEntry(String hash, int chunks) {}
    // 构造 RagIngestManifest 实例。
    public RagIngestManifest(RagVectorStoreConfig config) {
        this.manifestPath = Path.of(config.getDocumentBasePath(), ".ingested.json");
        this.configVersion = config.getConfigVersion();
        loadFromDisk();
    }

    // ==================== 公共 API ====================

    /** 判断指定文件是否需要重新摄入。 */
    public boolean needsIngestion(String fileName, String fileHash) {
        FileEntry existing = fileEntries.get(fileName);
        if (existing == null) {
            log.debug("[RAG][MANIFEST][NEW] fileName={}", fileName);
            return true;
        }
        if (!existing.hash().equals(fileHash)) {
            log.info("[RAG][MANIFEST][CHANGED] fileName={}, oldHash={}, newHash={}",
                    fileName, existing.hash().substring(0, Math.min(12, existing.hash().length())),
                    fileHash.substring(0, Math.min(12, fileHash.length())));
            return true;
        }
        return false;
    }
    /** 标记文件已摄入，更新清单并持久化到磁盘。 */
    public void markIngested(String fileName, String fileHash, int chunksAdded) {
        FileEntry old = fileEntries.put(fileName, new FileEntry(fileHash, chunksAdded));
        if (old != null) {
            totalChunks = totalChunks - old.chunks() + chunksAdded;
        } else {
            totalChunks += chunksAdded;
        }
        saveToDisk();
        log.info("[RAG][MANIFEST][MARKED] fileName={}, chunks={}, totalChunks={}",
                fileName, chunksAdded, totalChunks);
    }
    /** 获取已摄入的文件数量。 */
    public int getIngestedFileCount() {
        return fileEntries.size();
    }
    /** 获取知识库中所有 chunk 的总数。 */
    public int getTotalChunks() {
        return totalChunks;
    }
    /** 清空清单：移除所有文件条目，重置总 chunk 数为 0，并持久化到磁盘。 */
    public void clear() {
        fileEntries.clear();
        totalChunks = 0;
        saveToDisk();
        log.info("[RAG][MANIFEST][CLEARED] 清单已清空");
    }

    /** 移除清单中磁盘已不存在的文件条目。 */
    public void removeOrphanedEntries(Path docsPath) {
        List<String> orphans = fileEntries.keySet().stream()
                .filter(name -> !Files.exists(docsPath.resolve(name)))
                .toList();
        if (!orphans.isEmpty()) {
            orphans.forEach(name -> {
                FileEntry removed = fileEntries.remove(name);
                if (removed != null) totalChunks -= removed.chunks();
            });
            saveToDisk();
            log.info("[RAG][MANIFEST][CLEANUP] 清理了 {} 个孤立条目, totalChunks={}",
                    orphans.size(), totalChunks);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 从磁盘加载清单文件。
     *
     * 加载流程：
     *       ->检查清单文件是否存在，不存在则初始化为空清单
     *       ->读取 JSON 文件内容
     *       ->解析文件条目（兼容新旧两种格式）
     *       ->解析 total_chunks 字段
     *       ->检查 config_version 是否匹配，不匹配则清空清单触发全量重建
     *       ->加载失败时（文件损坏等），初始化为空清单
     */
    private void loadFromDisk() {
        if (!Files.exists(manifestPath)) {
            log.info("[RAG][MANIFEST][INIT] 清单文件不存在，将初始化为空: {}",
                    manifestPath.toAbsolutePath());
            return;
        }
        try {
            String json = Files.readString(manifestPath);
            Map<String, Object> data = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            Map<String, Object> files = (Map<String, Object>) data.getOrDefault("files",
                    Collections.emptyMap());
            for (var entry : files.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> m) {
                    fileEntries.put(entry.getKey(), new FileEntry(
                            (String) m.get("hash"),
                            ((Number) m.get("chunks")).intValue()));
                } else {
                    fileEntries.put(entry.getKey(),
                            new FileEntry((String) entry.getValue(), 0));
                }
            }

            totalChunks = data.containsKey("total_chunks")
                    ? ((Number) data.get("total_chunks")).intValue() : 0;

            int savedVersion = data.containsKey("config_version")
                    ? ((Number) data.get("config_version")).intValue() : 0;
            if (savedVersion != configVersion) {
                log.info("[RAG][MANIFEST][VERSION_MISMATCH] savedVersion={}, currentVersion={}, "
                        + "将清空清单触发全量重建", savedVersion, configVersion);
                fileEntries.clear();
                totalChunks = 0;
            } else {
                log.info("[RAG][MANIFEST][LOADED] files={}, totalChunks={}",
                        fileEntries.size(), totalChunks);
            }
        } catch (Exception e) {
            log.warn("[RAG][MANIFEST][LOAD_FAILED] 清单文件损坏，将初始化为空, reason={}",
                    e.getMessage());
            fileEntries.clear();
            totalChunks = 0;
        }
    }

    private synchronized void saveToDisk() {
        try {
            Files.createDirectories(manifestPath.getParent());
            Map<String, Object> filesMap = new LinkedHashMap<>();
            for (var entry : fileEntries.entrySet()) {
                var fe = entry.getValue();
                filesMap.put(entry.getKey(), Map.of("hash", fe.hash(), "chunks", fe.chunks()));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("version", 1);
            data.put("files", filesMap);
            data.put("last_ingested_at", LocalDateTime.now().format(DATE_FMT));
            data.put("total_chunks", totalChunks);
            data.put("config_version", configVersion);
            objectMapper.writeValue(manifestPath.toFile(), data);
        } catch (IOException e) {
            log.error("[RAG][MANIFEST][SAVE_FAILED] path={}, reason={}",
                    manifestPath.toAbsolutePath(), e.getMessage());
        }
    }
}
