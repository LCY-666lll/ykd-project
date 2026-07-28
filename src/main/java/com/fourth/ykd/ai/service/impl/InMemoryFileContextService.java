package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.PendingUserFile;
import com.fourth.ykd.ai.service.FileContextService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 在内存中保存短时有效的用户文件上下文。参考 InMemoryImageContextService。 */
@Service
public class InMemoryFileContextService implements FileContextService {

    @Value("${ilink.image-context-ttl-minutes:10}")
    private long fileContextTtlMinutes = 10;

    private final ConcurrentMap<String, PendingUserFile> files = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, byte[] fileBytes, String fileName) {
        if (!StringUtils.hasText(userId) || fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("文件上下文不能为空");
        }
        files.put(userId, new PendingUserFile(fileBytes,
                StringUtils.hasText(fileName) ? fileName : "未命名文件",
                detectContentType(fileName),
                Instant.now()));
    }

    @Override
    public Optional<PendingUserFile> findActive(String userId) {
        PendingUserFile file = files.get(userId);
        if (file == null) return Optional.empty();
        if (isExpired(file)) { files.remove(userId); return Optional.empty(); }
        return Optional.of(file);
    }

    @Override
    public void remove(String userId) { files.remove(userId); }

    @Scheduled(fixedDelay = 60_000)
    void removeExpiredFiles() {
        files.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    private boolean isExpired(PendingUserFile file) {
        return file.receivedAt().plus(Duration.ofMinutes(fileContextTtlMinutes)).isBefore(Instant.now());
    }

    private String detectContentType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".txt"))  return "text/plain";
        return "application/octet-stream";
    }
}
