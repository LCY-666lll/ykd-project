package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.PendingUserImage;

public interface ImageUnderstandingService {

    String understand(PendingUserImage image, String question);
}