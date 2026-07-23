package com.fourth.ykd.ai.utils;

import com.alibaba.cloud.ai.toolcalling.alitranslate.AliTranslateProperties;
import com.alibaba.cloud.ai.toolcalling.alitranslate.AliTranslateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TranslationTool {

    private final AliTranslateService service;

    public TranslationTool(AliTranslateProperties properties){
        this.service = new AliTranslateService(properties);
    }


    @Tool(description = """
            调用阿里翻译服务翻译文本。
            用户出现翻译、译成、转成、英文、日语、韩语等明确翻译意图时必须调用本工具，模型不得自行翻译。
            用户说上文、上面、这句、这段、刚才或前一条时，应从当前会话记忆中取得最近一条可翻译文本，作为 text 参数传入。
            sourceLanguage 和 targetLanguage 只能传 zh、en、ja、ko。
            """)
    public String translation(
            @ToolParam(description = "待翻译原文；可以是用户本轮提供的文本，或根据上文、上面、这句等指代从聊天记忆取得的实际文本", required = true)
            String text,
            @ToolParam(description = "原文语言代码，只能为 zh、en、ja、ko", required = true)
            String sourceLanguage,
            @ToolParam(description = "目标语言代码，只能为 zh、en、ja、ko", required = true)
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

            log.info("[AI][TOOL][TRANSLATION][SUCCESS] 翻译工具调用成功");

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