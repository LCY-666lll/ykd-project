package com.fourth.ykd.ai.utils;

import com.alibaba.cloud.ai.toolcalling.alitranslate.AliTranslateProperties;
import com.alibaba.cloud.ai.toolcalling.alitranslate.AliTranslateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TranslationTool {

    private final AliTranslateService service;

    public TranslationTool(AliTranslateProperties properties){
        this.service = new AliTranslateService(properties);
    }


    @Tool(
            description = "翻译文本。sourceLanguage 和 targetLanguage 必须使用语言代码：zh 中文、en 英语、ja 日语、ko 韩语。"
    )
    public String translation(
            String text,
            String sourceLanguage,
            String targetLanguage
    ) {
        log.info(
                "[AI][TOOL][TRANSLATION][START][翻译工具开始执行] text={}, sourceLanguage={}, targetLanguage={}",
                text,
                sourceLanguage,
                targetLanguage
        );

        try {
            AliTranslateService.Response response =
                    service.apply(
                            new AliTranslateService.Request(
                                    text,
                                    sourceLanguage,
                                    targetLanguage
                            )
                    );

            log.info("[AI][TOOL][TRANSLATION][OVER][翻译工具调用完毕]");

            return response.translatedTexts();
        } catch (RuntimeException exception) {
            log.warn(
                    "[AI][TOOL][TRANSLATION][FAILED][翻译工具调用失败] reason={}",
                    exception.getMessage()
            );
            throw exception;
        }
    }
}