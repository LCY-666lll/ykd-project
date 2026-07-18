package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.dto.PendingUserImage;

public interface ImageReferenceGenerationService {

    GeneratedImage generate(PendingUserImage referenceImage, String prompt);
}