package com.fourth.ykd.ai.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeAudioSpeechApi;
import com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel;
import org.springframework.ai.model.ApiKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DashScopeAudioConfig {

    @Bean
    public DashScopeAudioSpeechModel dashScopeAudioSpeechModel(
            @Value("${spring.ai.dashscope.api-key}") String apiKey) {

        ApiKey dashScopeApiKey = () -> apiKey;

        DashScopeAudioSpeechApi speechApi = DashScopeAudioSpeechApi.builder()
                .apiKey(dashScopeApiKey)
                .build();

        return new DashScopeAudioSpeechModel(speechApi);
    }
}
