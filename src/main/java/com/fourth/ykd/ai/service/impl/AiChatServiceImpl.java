package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.dto.PersistedChatMessage;
import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import com.fourth.ykd.ai.service.AiChatService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import com.fourth.ykd.ai.utils.*;
import com.fourth.ykd.ai.rag.RagHook;
import com.fourth.ykd.ai.rag.RagInterceptor;
import com.fourth.ykd.ai.rag.DocumentSearchTool;
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
            5. 用户追问某条新闻的详情、来源、原文或链接时，调用 search_realtime_information 补充对应信息，并在回答中展示相关链接。
            6. search_realtime_information 返回“实时搜索失败”时，不得再次更换关键词重试，不得调用其他工具，也不得使用训练数据补充新闻；只回复“暂未取得实时新闻，请稍后重试。”
            7. 用户出现翻译、译成、转成、英文、日语、韩语等翻译意图时，必须调用 translate_text，模型不得自行翻译。用户说上文、上面、这句、这段、刚才或前一条时，从聊天记忆取得最近一条可翻译文本后作为工具 text 参数。用户未说明目标语言时，只追问目标语言，不调用工具。translate_text 调用失败时，只说明翻译服务失败，不得自行补翻译。
            8. 用户提出算式或要求精确数值计算时，必须调用 calculate_math_expression，不得由模型自行估算。
            9. 用户查询真实当前日期、时间或计算当前日期与目标日期的间隔时，调用 get_time_info。
            10. 聊天历史中出现“【图片识别记忆】”时，它是用户此前发送图片的后台识别结果。用户询问图片、这张图、图中内容、上面的文字、里面的人或物等相关问题时，优先依据该记忆回答；与图片无关的问题忽略该记忆，不得编造图片中不存在的内容。
            11. 聊天历史和长期记忆仅用于理解用户的指代、延续同一任务、已确认的用户偏好或此前生成内容。
                    用户本轮提出独立的新问题时，不得把历史消息中的旧回答、旧事实或旧工具结果当作本轮答案的依据。
                    天气、新闻、时间、价格、政策等可能变化的信息，必须以本轮工具查询结果为准。
                    用户明确说“新话题”“不要参考历史”或“忽略之前内容”时，本轮不得使用聊天历史。
            12. 当用户要求创建、查看、删除或立即执行周期任务时，必须调用对应工具获取真实结果，
                严禁未调用工具就自行编造”任务已创建”或任务列表。
                create_periodic_task 用于重复执行的周期任务，schedule_task 用于一次性延迟任务，两者不可混用。
                - 创建：create_periodic_task（用户说”每天早上8点发新闻””每30分钟提醒””每隔10分钟发送天气”等重复性需求）
                - 查看：list_periodic_tasks（用户说”查看周期任务””有哪些周期任务””我设置了哪些任务”等）
                - 删除：delete_periodic_task（用户说”删除/取消某任务”时提供任务名或ID）
                - 立即执行：execute_periodic_task_now（用户说”立即/马上执行某任务”）
                用户仅陈述事实性习惯或客观规律（如”我每5分钟检查一次邮箱””高铁每30分钟一班”），
                而非指示你创建任务时，不得调用 create_periodic_task。
                当用户明确表达”设置””创建””帮我””给我安排”等操作意图且包含时间频率时，
                必须调用 create_periodic_task。
            13. 当用户要求设置一次性延迟提醒时，必须调用定时任务工具获取真实结果：
                - 创建：schedule_task（用户说”30分钟后提醒我喝水””5分钟后查询天气””1小时后发消息”）
                - 查看：list_scheduled_tasks（用户说”查看定时任务””有哪些待执行的任务”等）
                - 取消：cancel_scheduled_task（用户说”取消定时任务”时使用返回的taskId）
                用户仅说”提醒我””帮我记着”但未指定具体延迟时间时，应追问延迟多久。
                用户要求重复执行应调用 create_periodic_task，而非 schedule_task。
            14. 硬性约束：涉及周期任务或定时任务的任何操作，必须先调用对应工具，再基于工具返回值回复用户。
                在未收到工具返回值之前，严禁声称任务已创建、已删除或返回任何任务列表。
            15. 当用户询问某人/某项目的具体属性（技术栈、职责、感受、目标、经历、
                爱好、背景等细节信息）时，必须调用 search_knowledge_base 使用用户问题
                中的关键词精确检索。自动检索结果（═══ 标记中的内容）只是辅助参考，
                可能不完整，你必须以 search_knowledge_base 的返回结果为准。
                严禁在看到不完整的自动检索结果后就声称"知识库中没有相关信息"。
                search_knowledge_base 检索无结果时，才可说明"知识库中暂无相关信息"。
                search_knowledge_base 用于私有知识库文档，
                search_realtime_information 用于互联网公开信息，两者不可混用。
            """;

    private static final int PERSISTED_MEMORY_LIMIT = 20;

    private static final int MAX_PERSISTED_MEMORY_MESSAGES = 100;

    private final ChatClient springAiChatClient;

    private final MathCalculatorTool mathCalculatorTools;

    private final TimeTool timeTool;

    private final TranslationTool translationTool;

    private final WeatherTool weatherTool;

    private final BaiduSearchTool baiduSearchTool;

    private final PeriodicTaskTool periodicTaskTool;

    private final ScheduledTaskTool scheduledTaskTool;

    private final ChatMemory chatMemory;

    private final SqliteChatMessageRepository sqliteChatMessageRepository;

    private final RagHook ragHook;

    private final RagInterceptor ragInterceptor;

    private final DocumentSearchTool documentSearchTool;

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

        String answer = scheduledTaskTool.executeWithUserContext(normalizedConversationId, () ->
                springAiChatClient.prompt()
                        .system(TOOL_USAGE_INSTRUCTIONS + """
                                回答风格要求：保持简洁，用要点列表替代长篇段落，
                                避免不必要的emoji和装饰性格式，直接给出关键信息。
                                系统已支持 PDF、DOCX、XLSX 文件生成，以及文生图、参考图编辑、图片识别、
                                语音合成和知识库检索（RAG）。
                                不得声称这些能力不存在或无法使用；用户追问先前生成结果时，应基于聊天记忆如实说明。
                                当用户明确要求语音回复时，外层系统会把回答正文合成为语音；你只需正常回答用户的问题，
                                输出适合朗读的正文，不得声称自己只能文本交互、不能语音回复，也不要解释语音合成过程。

                                【RAG 知识库强制规则】
                                系统知识库中包含以下类型的文档：系统功能介绍、API接口文档、
                                常见问题解答、部署说明、用户个人信息和资料等。当用户询问任何
                                可能存在于这些文档中的信息（包括人名、地点、教育背景、工作经历
                                等个人信息），必须先调用 search_knowledge_base 检索知识库，
                                而不是直接回答"没有相关信息"或凭训练数据猜测。
                                当系统提示中出现「═══ 知识库检索结果 ═══」标记时，说明以下内容是
                                从本系统官方文档中检索到的确定事实。你必须遵守以下规则：
                                1. 将检索结果视为关于本系统的权威信息，优先于你的训练数据中的任何知识。
                                2. 直接引用检索结果中的具体内容，包括数字、路径、名称、配置值，
                                   以及感受、观点、经历等描述性文字。不得模糊化或使用"大概"
                                   "可能""常见做法""推测""一般"等不确定措辞。
                                3. 即使问题涉及"系统配置""后台参数""部署信息"等看似只有管理员才能
                                   知道的信息，只要检索结果中有明确答案，你就直接回答，不得声称
                                   "无法查看""无法确认""属于后台配置""需要问管理员"。
                                4. 如果你的训练知识与检索结果冲突，以检索结果为准，因为它是本系统的
                                   真实文档。
                                5. 检索结果无匹配或不足以回答时，才可以使用你的通用知识，并说明
                                   "知识库中暂无相关信息"。
                                5.1. 严禁编造具体的文件路径、文件名、端口号、配置值、API地址等
                                     精确信息。这些信息如果检索结果中没有明确包含，就必须说
                                     "知识库中暂无相关记录"，不得凭"常见做法""通常是什么"
                                     来推测。例如：检索结果只说"摄入清单"但未给出文件路径，
                                     就不要说data/rag-manifest.json；只说"日志文件"但未给出
                                     文件名，就不要说logs/rag.log。只有检索结果中明确写出
                                     的路径/名称/数字，你才能引用。
                                5.2. 对于感受、观点、经历、评价等描述性内容，必须严格基于检索结果
                                     原文，不得扩写、润色或添加检索结果中未提及的内容。检索结果只说
                                     "热爱技术落地带来的成就感"，你就只能引用这一点，不得补充"高压"
                                     "倦怠""自嘲""加班"等检索结果中不存在的描述。检索结果中的
                                     描述性内容是用户提供的确定信息，不是你可以自由发挥的素材。
                                6. 当系统提示中出现「自动检索未找到」标记时，说明自动检索未匹配到
                                   结果，但这不代表知识库中没有相关信息。你必须调用
                                   search_knowledge_base 尝试使用不同关键词检索，
                                   而不是直接回答"没有相关信息"或凭训练数据猜测。
                                   只有 search_knowledge_base 也返回无结果时，
                                   才能如实告知用户。
                                7. 知识库内容与聊天记忆冲突时，以知识库为准。聊天记忆中可能有
                                   过时的、推测的或之前编造的错误信息，不得将其当作事实重复。
                                8. 当检索到的知识库片段不包含用户所需的具体信息（如用户问"技术栈"
                                   但检索片段只有"基本信息"），必须调用 search_knowledge_base
                                   用更精确的关键词重新检索，而不是直接说"知识库没有相关信息"。
                                   知识库文档可能包含多个段落，某次检索的 top-2 片段未必覆盖
                                   所需内容，多尝试不同关键词。
                                """)
                        .user(normalizedMessage)
                        .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, normalizedConversationId))
                        .advisors(ragHook, ragInterceptor)
                        .tools(mathCalculatorTools, timeTool, baiduSearchTool, documentSearchTool, weatherTool, translationTool, periodicTaskTool, scheduledTaskTool)
                        .call()
                        .content()
        );

        // 模型回答成功后，把 bot 回复写入 SQLite。
        sqliteChatMessageRepository.save(
                normalizedConversationId,
                PersistedChatMessage.Role.ASSISTANT,
                answer
        );

        sqliteChatMessageRepository.softDeleteOldMessages(
                normalizedConversationId,
                MAX_PERSISTED_MEMORY_MESSAGES
        );

        return new AiChatResponse(answer);
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
        return switch (message.role()) {
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        };
    }

}