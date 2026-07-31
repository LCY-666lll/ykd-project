package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.dto.PersistedChatMessage;
import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import com.fourth.ykd.ai.memory.service.MemoryFormationService;
import com.fourth.ykd.ai.service.AiChatService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import com.fourth.ykd.ai.utils.*;
import com.fourth.ykd.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

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
            5. 用户追问某条新闻的详情、原文或链接，或明确要求重新查最新消息时，调用 search_realtime_information 补充对应信息，并在回答中展示相关链接。
               用户追问刚才新闻、上面内容、已生成文档或图片的“依据、根据、来源是什么”但没有要求原文、链接或重新查询时，优先依据聊天记忆中的【工具依据记忆】、【文件生成记忆】或上一轮回答说明，不要重复调用搜索工具。
            6. search_realtime_information 返回“实时搜索失败”时，不得再次更换关键词重试，不得调用其他工具，也不得使用训练数据补充新闻；只回复“暂未取得实时新闻，请稍后重试。”
            7. 用户出现翻译、译成、转成、英文、日语、韩语等翻译意图时，必须调用 translate_text，模型不得自行翻译。用户说上文、上面、这句、这段、刚才或前一条时，从聊天记忆取得最近一条可翻译文本后作为工具 text 参数。用户未说明目标语言时，只追问目标语言，不调用工具。translate_text 调用失败时，只说明翻译服务失败，不得自行补翻译。
            8. 用户提出算式或要求精确数值计算时，必须调用 calculate_math_expression，不得由模型自行估算。
            9. 用户查询真实当前日期、时间或计算当前日期与目标日期的间隔时，调用 get_time_info。
            10. 聊天历史中出现“【图片识别记忆】”时，它是用户此前发送图片的后台识别结果。用户询问图片、这张图、图中内容、上面的文字、里面的人或物等相关问题时，优先依据该记忆回答；与图片无关的问题忽略该记忆，不得编造图片中不存在的内容。
            11. 聊天历史和长期记忆仅用于理解用户的指代、延续同一任务、已确认的用户偏好或此前生成内容。
                    用户本轮提出独立的新问题时，不得把历史消息中的旧回答、旧事实或旧工具结果当作本轮答案的依据。
                    天气、新闻、时间、价格、政策等可能变化的信息，必须以本轮工具查询结果为准。
                    用户明确说“新话题”“不要参考历史”或“忽略之前内容”时，本轮不得使用聊天历史。
            """;

    private static final int PERSISTED_MEMORY_LIMIT = 20;

    private static final int MAX_PERSISTED_MEMORY_MESSAGES = 100;

    private static final int MEMORY_ENTRY_MAX_LENGTH = 2_000;

    private static final int GENERATED_CONTENT_MEMORY_MAX_LENGTH = 1_200;

    private final ChatClient springAiChatClient;

    private final MathCalculatorTool mathCalculatorTools;

    private final TimeTool timeTool;

    private final TranslationTool translationTool;

    private final WeatherTool weatherTool;

    private final BaiduSearchTool baiduSearchTool;

    private final ChatMemory chatMemory;

    private final SqliteChatMessageRepository sqliteChatMessageRepository;

    private final MemoryFormationService memoryFormationService;

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

        // 如果项目刚重启，内存没有历史消息，就从 SQLite 恢复最近 20 条
        restorePersistedMemory(normalizedConversationId);

        // 本次用户问题先写入 SQLite。
        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.USER,
                normalizedMessage
        );

        log.info("[AI][MEMORY_CHAT] conversationId={}", normalizedConversationId);

        String answer = springAiChatClient.prompt()
                .system(TOOL_USAGE_INSTRUCTIONS + """
                        系统已支持 PDF、DOCX、XLSX 文件生成，以及文生图、参考图编辑、图片识别和语音合成。
                        不得声称这些能力不存在或无法使用；用户追问先前生成结果时，应基于聊天记忆如实说明。
                        当用户明确要求语音回复时，外层系统会把回答正文合成为语音；你只需正常回答用户的问题，
                        输出适合朗读的正文，不得声称自己只能文本交互、不能语音回复，也不要解释语音合成过程。
                        """)
                .user(normalizedMessage)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, normalizedConversationId))
                .tools(mathCalculatorTools,timeTool,baiduSearchTool,weatherTool,translationTool)
                .call()
                .content();
        String normalizedAnswer = StringUtils.hasText(answer) ? answer.trim() : "我在的，有什么需要我帮忙？";
        // 模型回答成功后，把 bot 回复写入 SQLite。
        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.ASSISTANT,
                normalizedAnswer
        );

        sqliteChatMessageRepository.softDeleteOldMessages(
                normalizedConversationId,
                MAX_PERSISTED_MEMORY_MESSAGES
        );

        //普通聊天先返回用户，再把本轮对话提交到专用线程池异步形成长期记忆。
        memoryFormationService.submit(
                normalizedConversationId,
                normalizedConversationId,
                normalizedMessage,
                normalizedAnswer
        );

        return new AiChatResponse(normalizedAnswer);
    }

    /*明确记忆管理请求的同步处理流程：
     → 校验用户消息并恢复最近短期聊天记忆
     → 提取最近六条会话，用于解析“刚才那个”等指代
     → 保存用户消息到 chat_message
     → 同步调用 formSynchronously()
     → 等待 SQLite 实际写入完成
     → 把执行结果交给主模型
     → 主模型生成中文回复
     → 保存助手回复*/
    @Override
    public AiChatResponse manageMemory(
            String conversationId,
            String message
    ) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(40001, "消息内容不能为空");
        }

        String normalizedMessage = message.trim();
        String normalizedConversationId = StringUtils.hasText(conversationId)
                ? conversationId.trim()
                : DEFAULT_CONVERSATION_ID;

        restorePersistedMemory(normalizedConversationId);
        //在保存本轮用户消息前读取历史，避免把当前命令重复放进“近期上下文”。
        String recentConversationContext =
                buildRecentConversationContext(normalizedConversationId);

        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.USER,
                normalizedMessage
        );

        MemoryFormationService.FormationResult formationResult =
                memoryFormationService.formSynchronously(
                        normalizedConversationId,
                        normalizedConversationId,
                        normalizedMessage,
                        recentConversationContext
                );

        //把真实写库计数交给主模型，防止数据库没有成功却回复“已经记住”。
        String memoryExecutionContext = """
                这是一次明确的长期记忆管理请求。
                后台已经执行完毕，真实结果如下：
                completed=%s
                failedStage=%s
                candidateCount=%d
                createdCount=%d
                confirmedCount=%d
                replacedCount=%d
                deletedCount=%d
                ignoredCount=%d
                failedCount=%d

                必须严格依据上述真实结果回复用户：
                只有 createdCount、confirmedCount、replacedCount 或 deletedCount 大于 0 时，
                才能明确声称已经记住、确认、更新或删除。
                如果 completed=false 或 failedCount 大于 0，必须如实说明部分或全部操作没有成功。
                如果所有操作数量都是 0，不得声称已经完成，应请用户更明确地说明要记住或忘记什么。
                不要向用户展示字段名、内部计数、数据库、模型路由或系统实现细节。
                """.formatted(
                formationResult.completed(),
                formationResult.failedStage(),
                formationResult.candidateCount(),
                formationResult.createdCount(),
                formationResult.confirmedCount(),
                formationResult.replacedCount(),
                formationResult.deletedCount(),
                formationResult.ignoredCount(),
                formationResult.failedCount()
        );

        String answer = springAiChatClient.prompt()
                .system(TOOL_USAGE_INSTRUCTIONS + memoryExecutionContext)
                .user(normalizedMessage)
                .advisors(advisorSpec -> advisorSpec.param(
                        ChatMemory.CONVERSATION_ID,
                        normalizedConversationId
                ))
                .tools(
                        mathCalculatorTools,
                        timeTool,
                        baiduSearchTool,
                        weatherTool,
                        translationTool
                )
                .call()
                .content();

        String normalizedAnswer = StringUtils.hasText(answer)
                ? answer.trim()
                : "这次记忆操作没有得到可确认的结果，请再明确说明要记住或忘记什么。";

        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.ASSISTANT,
                normalizedAnswer
        );

        sqliteChatMessageRepository.softDeleteOldMessages(
                normalizedConversationId,
                MAX_PERSISTED_MEMORY_MESSAGES
        );

        return new AiChatResponse(normalizedAnswer);
    }
    /**
     * 构造明确记忆管理请求使用的近期会话上下文。
     * 最多读取最近六条短期消息，每条最多保留 600 个字符；
     * 该文本只交给提取模型解析本轮指代，不直接作为长期记忆写入数据库。
     */
    private String buildRecentConversationContext(String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        //只取最后六条消息，控制指代解析需要的上下文范围。
        int start = Math.max(0, messages.size() - 6);
        StringBuilder context = new StringBuilder();

        for (int index = start; index < messages.size(); index++) {
            Message message = messages.get(index);
            String role = message instanceof UserMessage ? "用户" : "助手";
            String text = message.getText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String normalizedText = text.trim();
            //单条历史过长时截断，避免文件内容或长回答挤占记忆提取提示词。
            if (normalizedText.length() > 600) {
                normalizedText = normalizedText.substring(0, 600);
            }
            context.append(role)
                    .append("：")
                    .append(normalizedText)
                    .append('\n');
        }
        return context.toString();
    }
    @Override
    public String prepareImagePrompt(String conversationId, String userText) {
        if (!StringUtils.hasText(userText)) {
            throw new BusinessException(40001, "图片请求不能为空");
        }
        String normalizedConversationId = StringUtils.hasText(conversationId)
                ? conversationId.trim()
                : DEFAULT_CONVERSATION_ID;
        restorePersistedMemory(normalizedConversationId);
        String imagePrompt = springAiChatClient.prompt()
                .system(TOOL_USAGE_INSTRUCTIONS + """
                        你负责为文生图模型准备最终中文提示词，不直接回答用户。
                        用户请求涉及新闻、时事、最新、今天、当前、实时天气、日期、计算或翻译时，必须先调用对应工具；
                        只能根据本轮工具结果写入相关事实，工具失败时不得使用训练数据补充事实。
                        最终只输出一段可直接交给文生图模型的中文画面描述，不要解释、不要 Markdown、不要标题。
                        """)
                .user(userText.trim())
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, normalizedConversationId))
                .tools(mathCalculatorTools, timeTool, baiduSearchTool, weatherTool, translationTool)
                .call()
                .content();
        return StringUtils.hasText(imagePrompt) ? imagePrompt.trim() : userText.trim();
    }

    /*如果当前内存需要恢复，就从数据库恢复持久化聊天记录*/
    private void restorePersistedMemory(String conversationId){
        //如果 ChatMemory 已经有消息,直接结束方法 ,不从 SQLite 重复恢复
        if (!chatMemory.get(conversationId).isEmpty()){
            return;
        }
        List<Message> messages = sqliteChatMessageRepository
                .findRecentActive(conversationId, PERSISTED_MEMORY_LIMIT)
                .stream()
                .map(this::toChatMemoryMessage)
                .toList();

        // SQLite 有历史消息时，才放入 Spring AI 的内存记忆。
        if (!messages.isEmpty()) {
            chatMemory.add(conversationId, messages);
        }

    }

    /*把自己封装的PersistedChatMessage类型转化为ChatMemory认识的SpringAI的Message类型
    SQLite: USER       → Spring AI: UserMessage
    SQLite: ASSISTANT  → Spring AI: AssistantMessage*/
    private Message toChatMemoryMessage(PersistedChatMessage message) {
        String content = compactRestoredMemory(message.content());
        return switch (message.role()) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
        };
    }

    private String compactRestoredMemory(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        int maxLength = (content.startsWith("【文件生成记忆】") || content.startsWith("【图片识别记忆】"))
                ? GENERATED_CONTENT_MEMORY_MAX_LENGTH
                : MEMORY_ENTRY_MAX_LENGTH;
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "…";
    }

}
