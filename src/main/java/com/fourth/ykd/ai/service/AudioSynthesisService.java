package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.GeneratedAudio;

//只负责把已有文本变成音频
public interface AudioSynthesisService {

    GeneratedAudio synthesize(String text);

}
