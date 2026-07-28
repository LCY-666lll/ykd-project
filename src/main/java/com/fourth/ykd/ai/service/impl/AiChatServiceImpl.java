package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.dto.PersistedChatMessage;
import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import com.fourth.ykd.ai.service.AiChatService;


import com.fourth.ykd.ai.utils.*;
import com.fourth.ykd.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/* 普通文本聊天：DeepSeek 仍然是文本对话模型，只是通过 Spring AI ChatClient 调用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private static final String DEFAULT_CONVERSATION_ID = "api-chat";

    private static final String TOOL_USAGE_INSTRUCTIONS = """

    你是微信机器人智能助手，所有回答使用中文。
    工具选择规则：
            1. 用户明确询问某地当前天气、温度、降雨或风力时才调用 query_current_weather；新闻、时事、政策、经济、科技动态问题不得调用该工具。
            1.1 用户本轮明确询问现在、当前、实时天气或今天此刻天气时，必须重新调用 query_current_weather，不得使用聊天记忆中的旧天气结果代替本轮查询。
            1.2 用户询问今天至后天的最高最低温、白天夜间天气、每日预报或未来降水时，必须调用 query_weather_forecast；本轮明确查询天气预报时必须重新调用，不得复用聊天记忆中的旧预报。
            1.3 query_current_weather 只回答当前实况，query_weather_forecast 只回答未来3日预报，不得混用两个工具的结果。
            2. 用户询问新闻、时事、最新动态或发生了什么时，应调用 search_realtime_information，不得使用训练数据编造实时信息。
            3. 用户未明确地区时，搜索并优先总结中国国家层面的新闻；首次新闻查询调用 search_realtime_information 时 num 传8。工具实际返回多少条有效结果就总结多少条，不得仅因少于8条而声称未搜到消息。每条新闻使用“标题 + 发生了什么 + 关键影响或进展”写成2到3句，只能依据本次搜索结果扩展事实；信息不足时如实简短说明，不得编造。不展示链接，末尾固定追加“您希望了解上述新闻的更多消息吗？”。
            4. 用户明确城市、省份、自治区或国家地区时，直接搜索并回答该地区新闻，不先返回全国新闻。
            5. 用户追问某条新闻的详情、来源、原文或链接时，调用 search_realtime_information 补充对应信息，并在回答中展示相关链接。
            6. search_realtime_information 返回“实时搜索失败”时，不得再次更换关键词重试，不得调用其他工具，也不得使用训练数据补充新闻；只回复“暂未取得实时新闻，请稍后重试。”
            7. 用户出现翻译、译成、转成、英文、日语、韩语等翻译意图时，必须调用 translate_text，模型不得自行翻译。用户说上文、上面、这句、这段、刚才或前一条时，从聊天记忆取得最近一条可翻译文本后作为工具 text 参数。用户未说明目标语言时，只追问目标语言，不调用工具。translate_text 调用失败时，只说明翻译服务失败，不得自行补翻译。
            8. 用户提出算式或要求精确数值计算时，必须调用 calculate_math_expression，不得由模型自行估算。
            9. 用户查询真实当前日期、时间或计算当前日期与目标日期的间隔时，调用 get_time_info。
            10. 聊天历史中出现“【图片识别记忆】”时，它是用户此前发送图片的后台识别结果。用户询问图片、这张图、图中内容、上面的文字、里面的人或物等相关问题时，优先依据该记忆回答；与图片无关的问题忽略该记忆，不得编造图片中不存在的内容。
            """;
    private final ChatClient springAiChatClient;

    private final MathCalculatorTool mathCalculatorTools;

    private final TimeTool timeTool;

    private final TranslationTool translationTool;

    private final WeatherTool weatherTool;

    private final BaiduSearchTool baiduSearchTool;

    private final SqliteChatMessageRepository repository;

    @Override
    public AiChatResponse chat(String message) {
        return chat(DEFAULT_CONVERSATION_ID, message);
    }

    @Override
    public AiChatResponse chat(String conversationId, String message) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(40001, "消息内容不能为空");
        }
        String normalizedMessage = message.trim();
        String normalizedConversationId = StringUtils.hasText(conversationId)
                ? conversationId.trim()
                : DEFAULT_CONVERSATION_ID;
        List<PersistedChatMessage> history = repository.findByConversationId(normalizedConversationId);
        StringBuilder context = new StringBuilder();
        for(PersistedChatMessage msg:history){
            context.append(msg.role())
                    .append(":")
                    .append(msg.content())
                    .append("\n");
        }
        context.append("\nUSER:")
                .append(normalizedMessage);

        log.info("[AI][MEMORY_CHAT] conversationId={}", normalizedConversationId);
        String answer = springAiChatClient.prompt()
                .system(TOOL_USAGE_INSTRUCTIONS + """

                        系统已支持 PDF、DOCX、XLSX 文件生成，以及文生图、参考图编辑、图片识别和语音合成。
                        不得声称这些能力不存在或无法使用；用户追问先前生成结果时，应基于聊天记忆如实说明。
                        当用户明确要求语音回复时，外层系统会把回答正文合成为语音；你只需正常回答用户的问题，
                        输出适合朗读的正文，不得声称自己只能文本交互、不能语音回复，也不要解释语音合成过程。
                        """)
                .user(String.valueOf(context))
                .tools(mathCalculatorTools,timeTool,baiduSearchTool,weatherTool,translationTool)
                .call()
                .content();
        repository.save(normalizedConversationId,"USER",normalizedMessage);
        repository.save(normalizedConversationId,"ASSISTANT",answer);
        return new AiChatResponse(answer);
    }



}