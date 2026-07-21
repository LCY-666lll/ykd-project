package com.fourth.ykd.ai.infrastructure.dashscope;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
/*读取 DashScope 配置：apiKey、生图模型、视觉模型、TTS 模型等。*/
@Getter
@Setter
@ConfigurationProperties(prefix = "dashscope")
public class DashScopeProperties {

    private String apiBaseUrl;

    private String apiKey;

    private String imageGenerationModel;

    private String visionModel;

    private String ttsModel;

    private String ttsVoice;

    private String ttsFormat;

    private Integer ttsSampleRate;
}
