package com.fourth.ykd.ai.service.impl;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.service.ImageContextService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 在内存中保存短时有效的用户图片上下文。 */
@Service
public class InMemoryImageContextService implements ImageContextService {
    @Value("${ilink.image-context-ttl-minutes:10}")
    private long imageContextTtlMinutes = 10;
    private final ConcurrentMap<String, PendingUserImage> images = new ConcurrentHashMap<>();
    /** 保存用户最新图片。 */
    @Override public void save(String userId, byte[] imageBytes) {
        if (!StringUtils.hasText(userId) || imageBytes == null || imageBytes.length == 0) throw new IllegalArgumentException("图片上下文不能为空");
        images.put(userId, new PendingUserImage(imageBytes, detectContentType(imageBytes), Instant.now()));
    }
    /** 查询未过期图片。 */
    @Override public Optional<PendingUserImage> findActive(String userId) {
        PendingUserImage image = images.get(userId);
        if (image == null) return Optional.empty();
        if (isExpired(image)) { images.remove(userId, image); return Optional.empty(); }
        return Optional.of(image);
    }
    /** 删除指定图片上下文。 */
    @Override public void remove(String userId, PendingUserImage image) { images.remove(userId, image); }
    /** 清理过期图片上下文。 */
    @Scheduled(fixedDelay = 60_000) void removeExpiredImages() { images.entrySet().removeIf(entry -> isExpired(entry.getValue())); }
    /** 使用现有配置判断是否过期。 */
    private boolean isExpired(PendingUserImage image) { return image.receivedAt().plus(Duration.ofMinutes(imageContextTtlMinutes)).isBefore(Instant.now()); }
    /** 根据文件头推断图片类型。 */
    private String detectContentType(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) return "image/jpeg";
        if (bytes.length >= 6 && bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46) return "image/gif";
        return "image/jpeg";
    }
}
