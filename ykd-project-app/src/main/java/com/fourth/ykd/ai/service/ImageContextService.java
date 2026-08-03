package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.PendingUserImage;
import java.util.Optional;

public interface ImageContextService {

    void save(String userId, byte[] imageBytes);

    Optional<PendingUserImage> findActive(String userId);

    void remove(String userId, PendingUserImage image);
}