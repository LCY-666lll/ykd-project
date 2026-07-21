package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.service.ImageContextService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class InMemoryImageContextService implements ImageContextService {

    private static final Duration IMAGE_CONTEXT_TTL = Duration.ofMinutes(10);

    private final ConcurrentMap<String, PendingUserImage> images = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, byte[] imageBytes) {
        if (!StringUtils.hasText(userId) || imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("图片上下文不能为空");
        }
        images.put(userId, new PendingUserImage(
                imageBytes,
                detectContentType(imageBytes),
                Instant.now()
        ));
    }

    @Override
    public Optional<PendingUserImage> findActive(String userId) {
        PendingUserImage image = images.get(userId);
        if (image == null) {
            return Optional.empty();
        }
        if (isExpired(image)) {
            images.remove(userId, image);
            return Optional.empty();
        }
        return Optional.of(image);
    }

    @Override
    public void remove(String userId, PendingUserImage image) {
        images.remove(userId, image);
    }

    @Scheduled(fixedDelay = 60_000)
    void removeExpiredImages() {
        images.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    private boolean isExpired(PendingUserImage image) {
        return image.receivedAt().plus(IMAGE_CONTEXT_TTL).isBefore(Instant.now());
    }

    private String detectContentType(byte[] bytes) {
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xFF
                && bytes[1] == (byte) 0xD8
                && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 6
                && bytes[0] == 0x47
                && bytes[1] == 0x49
                && bytes[2] == 0x46) {
            return "image/gif";
        }
        return "image/jpeg";
    }
}