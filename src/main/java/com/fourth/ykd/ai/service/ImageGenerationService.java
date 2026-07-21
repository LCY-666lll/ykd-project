package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.GeneratedImage;

public interface ImageGenerationService {

    GeneratedImage generate(String prompt);
}