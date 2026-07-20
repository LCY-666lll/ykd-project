package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.GeneratedAudio;

public interface AudioSynthesisService {

    GeneratedAudio synthesize(String text);

}
