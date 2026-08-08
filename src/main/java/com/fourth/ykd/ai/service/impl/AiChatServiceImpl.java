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

/*
普通文本聊天：DeepSeek 仍然是文本对话模型，只是通过 Spring AI ChatClient 调用。
 AiChatServiceImpl
 ├→ springAiChatClient（SpringAiChatConfig 组装：3 Advisor）
 ├→ 5 个工具 Bean（WeatherTool/BaiduSearchTool/TimeTool/TranslationTool/MathCalculatorTool）
 ├→ ChatMemory（短期记忆）
 ├→ SqliteChatMessageRepository（会话落库）
 └→ MemoryFormationService（异步长期记忆）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private static final String DEFAULT_CONVERSATION_ID = "api-chat";

    //模型什么时候必须调用哪个工具，什么时候不能使用旧记忆代替实时查询。
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
            11. 聊天历史和长期记忆仅用于理解用户的指代延续同一任务、已确认的用户偏好或此前生成内容。
                    用户本轮提出独立的新问题时，不得把历史消息中的旧回答、旧事实或旧工具结果当作本轮答案的依据。
                    天气、新闻、时间、价格、政策等可能变化的信息，必须以本轮工具查询结果为准。
                    用户明确说“新话题”“不要参考历史”或“忽略之前内容”时，本轮不得使用聊天历史。
            """;

    private static final String CURRENT_CAPABILITY_INSTRUCTIONS = """

            当前系统能力说明：当用户问“你能做什么”“你能干啥”“你的能力是什么”时，必须完整列出以下全部八类能力；不得为了简短而省略任何一类，也不得只回答本轮已挂载的工具。
            1. 对话与上下文：中文问答、写作、分析，以及结合近期聊天记录理解追问。
            2. 实时工具：实时新闻搜索、当前天气和未来天气预报、时间日期、数学计算及翻译。
            3. 文件：生成并通过微信发送 PDF、Word/DOCX、Excel/XLSX 文件。
            4. 图片：文生图、基于参考图编辑图片、图片识别。
            5. 语音：根据用户明确要求发送语音回复。
            6. 记忆：按用户明确请求记住、查询、修改或删除长期偏好和事实。
            7. 公开网页：用户提供明确的公开 http 或 https 网址，并说明查看、总结、查找、点击、筛选或翻页等动作时，可通过真实浏览器访问公开页面并返回结果。
            8. 安全边界：浏览器仅处理公开网页；不得登录、接收或填写验证码、短信或扫码验证，不得支付、购买、发布、删除、上传或下载文件，不得读取账号密码、Cookie、Token、个人资料、订单、私信或其他私人数据。

            对能力询问，使用编号列表逐项说明以上八类；不要仅说“天气、新闻、计算、翻译”等基础能力，不要根据用户历史偏好扩展为无关的行程承诺。
            本轮 ChatClient 未直接挂载文件、图片、语音或浏览器工具，不代表系统没有这些能力：这些能力由外层意图分流和微信发送链路执行，必须如实说明。

            若用户的请求触及安全边界，应明确说明具体受限操作；不要否认其他已具备的能力。
            """;
    private static final int PERSISTED_MEMORY_LIMIT = 20;
    private static final String VOICE_REPLY_INSTRUCTIONS = """
            本轮回答将由系统合成为微信语音。只输出应当朗读给用户的自然中文正文。
            严禁提及模型、ChatClient、工具是否挂载、语音或音频能否生成、语音如何合成、消息是否发送成功、
            外层链路、上传、CDN、失败原因或让用户重新触发语音。
            不要声明“我不能生成语音”或“我没有语音工具”；直接回答用户的问题即可。
            """;

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

    //从本轮用户消息和助手回复中提取长期记忆候选
    private final MemoryFormationService memoryFormationService;

    @Override
    public AiChatResponse chat(String message) {
        return chat(DEFAULT_CONVERSATION_ID, message);
    }

    @Override
    public AiChatResponse chat(String conversationId, String message) {
        return chat(conversationId, message, "");
    }

    @Override
    public AiChatResponse chatForVoiceReply(String conversationId, String message) {
        return chat(conversationId, message, VOICE_REPLY_INSTRUCTIONS);
    }

    /**
     * 执行普通聊天或语音回答文本生成。
     *
     * @param conversationId 当前用户或会话 ID
     * @param message 用户本轮消息
     * @param additionalSystemInstructions 额外系统规则，
     *                                     普通聊天为空，
     *                                     语音回答时传语音专用规则
     * @return 包含最终文字回答的 AiChatResponse
     *
     * 核心流程：
     * 1. 校验并规范参数；
     * 2. ChatMemory 为空时，从 SQLite 恢复最近消息；
     * 3. 将本轮用户消息持久化；
     * 4. 通过 Advisor 参数指定当前会话；
     * 5. 挂载实时工具并调用模型；
     * 6. 保存助手回答；
     * 7. 清理过旧聊天记录；
     * 8. 异步提交长期记忆形成；
     * 9. 返回 AiChatResponse。
     */
    private AiChatResponse chat(
            String conversationId,
            String message,
            String additionalSystemInstructions
    ) {
        // 空消息不能交给模型。
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(
                    40001,
                    "消息内容不能为空"
            );
        }

        // 清理用户输入和会话 ID。
        String normalizedMessage =
                message.trim();

        String normalizedConversationId =
                StringUtils.hasText(conversationId)
                        ? conversationId.trim()
                        : DEFAULT_CONVERSATION_ID;

        /*
         * 如果应用刚刚重启，ChatMemory 中没有历史，
         * 就从 SQLite 恢复最近 20 条聊天记录。
         */
        restorePersistedMemory(
                normalizedConversationId
        );

        // 模型调用前，先持久化本轮用户消息。
        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.USER,
                normalizedMessage
        );

        log.info(
                "[AI][MEMORY_CHAT] conversationId={}",
                normalizedConversationId
        );

        /*
         * 构造本轮模型请求。
         * advisors 中传入 conversationId，
         * 让短期记忆 Advisor 和其他自定义 Advisor
         * 知道本轮应该读取哪个用户的数据。
         */
        String answer =
                springAiChatClient.prompt()
                        .system(
                                buildChatSystemInstructions()
                                        + additionalSystemInstructions
                        )
                        .user(normalizedMessage)
                        .advisors(
                                advisorSpec ->
                                        advisorSpec.param(
                                                ChatMemory.CONVERSATION_ID,
                                                normalizedConversationId
                                        )
                        )
                        .tools(
                                mathCalculatorTools,
                                timeTool,
                                baiduSearchTool,
                                weatherTool,
                                translationTool
                        )
                        .call()
                        .content();

        // 模型空回答时提供默认内容。
        String normalizedAnswer =
                StringUtils.hasText(answer)
                        ? answer.trim()
                        : "我在的，有什么需要我帮忙？";

        // 模型成功回答后，持久化助手消息。
        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.ASSISTANT,
                normalizedAnswer
        );

        // 每个会话只保留最近 100 条活跃持久化消息。
        sqliteChatMessageRepository
                .softDeleteOldMessages(
                        normalizedConversationId,
                        MAX_PERSISTED_MEMORY_MESSAGES
                );

        /*
         * 主回复已经生成，
         * 后台异步分析本轮对话是否值得形成长期记忆。
         */
        memoryFormationService.submit(
                normalizedConversationId,
                normalizedConversationId,
                normalizedMessage,
                normalizedAnswer
        );

        // 将最终文字包装后返回给 Processor。
        return new AiChatResponse(
                normalizedAnswer
        );
    }

    static String buildChatSystemInstructions() {
        return TOOL_USAGE_INSTRUCTIONS + CURRENT_CAPABILITY_INSTRUCTIONS;
    }

    /**
     * 同步处理用户明确提出的长期记忆管理请求。
     *
     * @param conversationId 用户或会话 ID
     * @param message 记住、修改、纠正、删除等明确指令
     * @return 根据真实数据库执行结果生成的回复
     *
     * 与普通聊天不同：
     * 普通聊天异步形成记忆；
     * 明确记忆管理必须等待真实写库结果。
     */
    @Override
    public AiChatResponse manageMemory(
            String conversationId,
            String message
    ) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(
                    40001,
                    "消息内容不能为空"
            );
        }

        String normalizedMessage =
                message.trim();

        String normalizedConversationId =
                StringUtils.hasText(conversationId)
                        ? conversationId.trim()
                        : DEFAULT_CONVERSATION_ID;

        // 先恢复近期短期聊天，便于解析“刚才那个”等指代。
        restorePersistedMemory(
                normalizedConversationId
        );

        /*
         * 必须在保存当前记忆命令前提取历史，
         * 避免当前命令既出现在历史中，又作为本轮消息重复传入。
         */
        String recentConversationContext =
                buildRecentConversationContext(
                        normalizedConversationId
                );

        // 保存用户本轮明确记忆命令。
        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.USER,
                normalizedMessage
        );

        /*
         * 同步执行长期记忆形成和真实数据库写入，
         * 返回创建、确认、替换、删除、失败等真实计数。
         */
        MemoryFormationService.FormationResult
                formationResult =
                memoryFormationService.formSynchronously(
                        normalizedConversationId,
                        normalizedConversationId,
                        normalizedMessage,
                        recentConversationContext
                );

        // 根据真实写库结果生成确定性回复，不虚假承诺。
        String normalizedAnswer =
                buildMemoryManagementReply(
                        formationResult
                );

        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.ASSISTANT,
                normalizedAnswer
        );

        sqliteChatMessageRepository
                .softDeleteOldMessages(
                        normalizedConversationId,
                        MAX_PERSISTED_MEMORY_MESSAGES
                );

        return new AiChatResponse(
                normalizedAnswer
        );
    }

    static String buildMemoryManagementReply(
            MemoryFormationService.FormationResult formationResult
    ) {
        if (!formationResult.completed()) {
            return "本次长期记忆处理未完成，请稍后重试或换一种更明确的说法。";
        }

        int successfulCount = formationResult.createdCount()
                + formationResult.confirmedCount()
                + formationResult.replacedCount()
                + formationResult.deletedCount();

        if (formationResult.failedCount() > 0) {
            return successfulCount > 0
                    ? "本次长期记忆已部分处理完成，部分内容未能保存，请稍后重试。"
                    : "本次长期记忆处理未完成，请稍后重试或换一种更明确的说法。";
        }

        StringBuilder reply = new StringBuilder();

        if (formationResult.createdCount() > 0) {
            reply.append("已保存本次长期记忆。");
        }

        if (formationResult.confirmedCount() > 0) {
            reply.append("已确认本次长期记忆。");
        }

        if (formationResult.replacedCount() > 0) {
            reply.append("已更新本次长期记忆。");
        }

        if (formationResult.deletedCount() > 0) {
            reply.append("已删除相关长期记忆。");
        }

        if (reply.isEmpty()) {
            return "本次没有识别到需要保存或变更的长期记忆，请说得更明确一些。";
        }

        return reply.toString();
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
