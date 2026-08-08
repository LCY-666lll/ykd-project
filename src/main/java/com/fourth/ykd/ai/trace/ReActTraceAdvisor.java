package com.fourth.ykd.ai.trace;

import com.fourth.ykd.ai.dto.PersistedChatMessage;
import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import com.fourth.ykd.exception.BusinessException;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.util.StringUtils;

/** 仅记录既有工具循环，不参与模型决策或改变模型返回。
 * 在父类工具循环上增加记录和限制
 * 记录轨迹、限制轮数、沉淀证据记忆
 * 套在 Spring AI 工具调用循环外面的一层“记录器 + 限流器”。它不负责判断该调用哪个工具，
 * 但会记录模型调用工具的过程、限制最多 8 轮工具调用，并把工具结果保存成后续会话可参考的“工具依据记忆”。
 * adviseCall()                         —— 整个 Advisor 的入口
 *     ↓
 * 创建 TraceState                     —— 给本轮请求创建独立追踪状态
 *     ↓
 * 交给父类 ToolCallAdvisor            —— 让父类真正开始执行工具调用循环
 *
 * doInitializeLoop()                   —— 工具循环刚开始时执行
 *     ↓
 * markKnownObservations()              —— 标记历史里已经存在的工具结果
 *     ↓
 * 记录 START                           —— 记录本轮 ReAct 开始日志
 *
 * doBeforeCall()                       —— 每次准备调用模型前执行
 *     ↓
 * logNewObservations()                 —— 查找并记录新出现的工具结果
 *         ↓
 *         toolResponses()              —— 从消息列表中提取 ToolResponse
 *         ↓
 *         key()                        —— 给工具结果生成去重标识
 *         ↓
 *         sanitize()                   —— 工具结果脱敏、去换行、截断
 *         ↓
 *         isEvidenceTool()             —— 判断该工具结果是否值得保存
 *         ↓
 *         保存 evidenceEntries         —— 暂存本轮可作为依据的工具结果
 *         ↓
 *         记录 OBSERVATION             —— 记录工具返回结果日志
 *
 * 模型返回                             —— 模型完成当前这一轮调用
 *     ↓
 * doAfterCall()                        —— 每次模型返回后进行统计和检查
 *     ↓
 * step++                               —— 模型调用轮数加 1
 *     ↓
 * addUsage()                           —— 累加本轮 Token 消耗
 *     ↓
 * 检测 reasoning                      —— 判断 DeepSeek 是否产生推理内容
 *     ↓
 * 检查 toolCalls                      —— 看模型这次是否要求调用工具
 *     ↓
 * 限制 maxToolRounds                  —— 超过最大工具轮数就强制停止
 *     ↓
 * 记录 ACTION                          —— 记录模型准备调用哪个工具
 *
 * 有工具调用                           —— 模型还需要外部工具结果
 *     ↓
 * 父类执行工具                         —— ToolCallAdvisor 真正调用 Tool
 *     ↓
 * 再次进入 doBeforeCall()              —— 工具结果加入 Prompt 后再次调用模型前检查
 *
 * 没有工具调用                         —— 模型已经得到最终答案
 *     ↓
 * 循环结束                             —— 本轮工具调用链结束
 *     ↓
 * doFinalizeLoop()                     —— 整轮 ReAct 结束后的收尾方法
 *     ↓
 * elapsedMillis()                      —— 计算本轮总耗时
 *     ↓
 * 记录 FINAL                           —— 记录轮数、Token、耗时等汇总日志
 *     ↓
 * saveToolEvidenceMemory()             —— 保存本轮有价值的工具依据
 *         ↓
 *         trimEvidence()               —— 限制所有工具依据的总长度
 *         ↓
 *         ChatMemory 保存              —— 保存到当前用户短期聊天上下文
 *         ↓
 *         SQLite 保存                  —— 持久化工具依据，重启后还能恢复
 */
@Slf4j
//adviseCall被调用后，ToolCallAdvisor会自己驱动"发请求给模型 → 模型返回工具调用 → 执行工具 → 把工具结果加回 Prompt → 再次调用模型"的循环，直到模型不再请求工具。
public class ReActTraceAdvisor extends ToolCallAdvisor {

    private static final String TRACE_CONTEXT_KEY = ReActTraceAdvisor.class.getName() + ".trace";
    private static final int MAX_LOG_LENGTH = 1_500;
    private static final int MAX_EVIDENCE_MEMORY_LENGTH = 4_000;
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 8;
    private static final Pattern SENSITIVE_VALUE_PATTERN = Pattern.compile(
            "(?i)(\\\"?(?:api[-_]?key|access[-_]?key|secret|token|password|authorization)\\\"?\\s*[:=]\\s*)(\\\"(?:\\\\.|[^\\\"])*\\\"|[^,}\\s]+)"
    );

    private final ChatMemory chatMemory;
    private final SqliteChatMessageRepository sqliteChatMessageRepository;
    private final int maxToolRounds;

    public ReActTraceAdvisor(ToolCallingManager toolCallingManager, int advisorOrder,
            ChatMemory chatMemory, SqliteChatMessageRepository sqliteChatMessageRepository) {
        this(toolCallingManager, advisorOrder, chatMemory, sqliteChatMessageRepository, DEFAULT_MAX_TOOL_ROUNDS);
    }

    public ReActTraceAdvisor(ToolCallingManager toolCallingManager,
                             int advisorOrder,
                             ChatMemory chatMemory,
                             SqliteChatMessageRepository sqliteChatMessageRepository,
                             //自定义最大轮数
                             int maxToolRounds) {
        /*父类需要：ToolCallingManager → 负责执行模型请求中的工具调用(真正执行工具调用循环的是父类)
        advisorOrder → 当前 Advisor 在整个 Advisor 链中的相对顺序*/
        super(toolCallingManager, advisorOrder);
        if (maxToolRounds < 1) {
            throw new IllegalArgumentException("必须至少允许一轮工具调用");
        }
        this.chatMemory = chatMemory;
        this.sqliteChatMessageRepository = sqliteChatMessageRepository;
        this.maxToolRounds = maxToolRounds;
    }

    //当前类最外层入口。每次 ChatClient 调用进入这个 Advisor 时，就先执行它。
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        //复制请求上下文
        Map<String, Object> context = new HashMap<>(request.context());
        //创建 TraceState
        context.put(TRACE_CONTEXT_KEY, new TraceState(resolveConversationId(context)));
        //放进 context，先塞入追踪对象，然后让父类继续。
        return super.adviseCall(request.mutate().context(context).build(), chain);
    }

    //循环开始时执行：标记 Prompt 中原有的旧工具结果
    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        TraceState trace = traceState(request.context());
        if (trace != null) {
            //把 Prompt 中原本存在的旧工具结果标记掉
            trace.markKnownObservations(request.prompt().getInstructions());
            log.info("[AI][REACT][START] traceId={}, conversationId={}", trace.traceId, trace.conversationId);
        }
        return request;
    }

    //每次调用模型前执行，在模型即将再次调用前，检查 Prompt 中有没有新的工具结果。
    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        TraceState trace = traceState(request.context());
        if (trace != null) {
            //记录的是：工具刚执行完的结果
            trace.logNewObservations(request.prompt().getInstructions());
        }
        return request;
    }

    //每次模型返回后执行：模型这次返回了什么工具调用
    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        TraceState trace = traceState(response.context());
        ChatResponse chatResponse = response.chatResponse();
        if (trace == null || chatResponse == null || chatResponse.getResult() == null) {
            return response;
        }
        //每次模型返回都加一次
        trace.step++;
        //把这一次模型调用的 Token 加到总数里
        trace.addUsage(chatResponse.getMetadata() == null ? null : chatResponse.getMetadata().getUsage());
        AssistantMessage output = chatResponse.getResult().getOutput();
        if (output instanceof DeepSeekAssistantMessage deepSeekMessage
                && StringUtils.hasText(deepSeekMessage.getReasoningContent())) {
            trace.reasoningPresent = true;
            log.debug("[AI][REACT][THOUGHT] traceId={}, step={}, reasoningLength={}", trace.traceId, trace.step,
                    deepSeekMessage.getReasoningContent().length());
        }
        //拿出工具调用
        List<AssistantMessage.ToolCall> toolCalls =
                output == null ? List.of() : output.getToolCalls();
        //限制工具轮数
        if (!toolCalls.isEmpty()) {
            if (trace.toolRounds >= maxToolRounds) {
                log.warn("[AI][REACT][STOP] traceId={}, toolRounds={}, reason=MAX_TOOL_ROUNDS",
                        trace.traceId, trace.toolRounds);
                throw new BusinessException(50007, "本次任务需要的工具调用过多，已停止执行，请拆分请求后重试");
            }
            trace.toolRounds++;
        }
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            log.debug("[AI][REACT][ACTION] traceId={}, step={}, tool={}, argumentLength={}", trace.traceId, trace.step,
                    toolCall.name(), StringUtils.hasText(toolCall.arguments()) ? toolCall.arguments().length() : 0);
        }        return response;
    }

    //整个循环结束：记录整轮汇总，保存工具依据
    @Override
    protected ChatClientResponse doFinalizeLoop(ChatClientResponse response, CallAdvisorChain chain) {
        TraceState trace = traceState(response.context());
        if (trace != null) {
            log.info("[AI][REACT][FINAL] traceId={}, steps={}, toolRounds={}, reasoningPresent={}, promptTokens={}, completionTokens={}, totalTokens={}, elapsedMs={}",
                    trace.traceId, trace.step, trace.toolRounds, trace.reasoningPresent, trace.promptTokens, trace.completionTokens,
                    trace.promptTokens + trace.completionTokens, trace.elapsedMillis());
            saveToolEvidenceMemory(trace);
        }
        return response;
    }

    //工具返回结果在写入日志之前，先整理、脱敏和截断：去换行+隐藏密码+截断长内容（控制单个工具结果最多 1500 字）
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = SENSITIVE_VALUE_PATTERN.matcher(value.replaceAll("[\\r\\n]+", " ").trim())
                .replaceAll("$1\"***\"");
        return sanitized.length() <= MAX_LOG_LENGTH ? sanitized : sanitized.substring(0, MAX_LOG_LENGTH) + "...";
    }

    //找到当前用户：工具依据保存到这个用户下面
    private static String resolveConversationId(Map<String, Object> context) {
        Object conversationId = context.get(ChatMemory.CONVERSATION_ID);
        return conversationId == null ? "default" : String.valueOf(conversationId);
    }

    //在整轮工具调用结束后执行。作用是：把刚才收集的天气、时间、搜索等结果，保存到 ChatMemory 和 SQLite。
    private void saveToolEvidenceMemory(TraceState trace) {
        if (trace.evidenceEntries.isEmpty()) {
            return;
        }
        String content = """
        【工具依据记忆】
        本轮自动调用工具取得以下结果。
        后续用户追问刚才结果、依据、来源或已生成内容时优先参考；
        用户明确要求重新查询实时信息时才重新调用工具。
        %s
        """.formatted(trimEvidence(String.join(System.lineSeparator() + System.lineSeparator(), trace.evidenceEntries)));
        try {
            chatMemory.add(trace.conversationId, List.of(new AssistantMessage(content)));
            sqliteChatMessageRepository.save(trace.conversationId, PersistedChatMessage.Role.ASSISTANT, content);
            sqliteChatMessageRepository.softDeleteOldMessages(trace.conversationId, 100);
        } catch (RuntimeException exception) {
            log.warn("[AI][REACT][EVIDENCE_MEMORY_SAVE_FAILED] traceId={}, conversationId={}",
                    trace.traceId, trace.conversationId, exception);
        }
    }

    //截断工具依据记忆：控制所有工具结果合起来最多 4000 字
    private static String trimEvidence(String value) {
        return value.length() <= MAX_EVIDENCE_MEMORY_LENGTH
                ? value
                : value.substring(0, MAX_EVIDENCE_MEMORY_LENGTH) + "...";
    }

    //判断当前工具的结果，是否需要保存成“工具依据记忆”
    private static boolean isEvidenceTool(String toolName) {
        return Set.of("search_realtime_information", "query_current_weather", "query_weather_forecast",
                "get_time_info", "translate_text", "calculate_math_expression").contains(toolName);
    }


    //从上下文取追踪对象：从 context 中取：TraceState
    private static TraceState traceState(Map<String, Object> context) {
        //把当前请求对应的追踪状态取出来
        Object trace = context.get(TRACE_CONTEXT_KEY);
        return trace instanceof TraceState traceState ? traceState : null;
    }

   /* 某一轮聊天的完整工具调用追踪数据。
    例如用户 A 问了一次天气，就会创建一个 TraceState。
    下一次再问时间，又会创建新的 TraceState。*/
    private static final class TraceState {
        //随机 8 位 ID
        private final String traceId = UUID.randomUUID().toString().substring(0, 8);
        private final String conversationId;
        //记录这一轮开始时间
        private final long startedAtNanos = System.nanoTime();
        //保存已经记录过的工具结果标识
        private final Set<String> loggedObservations = new HashSet<>();
        //保存本轮需要写入记忆的工具结果
        private final List<String> evidenceEntries = new ArrayList<>();
        //模型一共返回了几次
        private int step;
        //模型一共有几轮要求调用工具
        private int toolRounds;
        private int promptTokens;
        private int completionTokens;
        //是否检测到 DeepSeek 推理内容
        private boolean reasoningPresent;

        private TraceState(String conversationId) {
            this.conversationId = conversationId;
        }

        //标记旧结果：工具循环刚开始时，把 Prompt 里本来就存在的工具结果先标记为“旧结果”。
        private void markKnownObservations(List<Message> messages) {
            toolResponses(messages).forEach(response -> loggedObservations.add(key(response)));
        }

        //记录本轮新工具结果
        private void logNewObservations(List<Message> messages) {
            //找到所有工具结果
            toolResponses(messages)
                    .filter(response -> loggedObservations.add(key(response)))
                    .forEach(response -> {
                        String result = sanitize(response.responseData());
                        //如果工具是天气、搜索、时间、翻译、计算，就保存。
                        if (isEvidenceTool(response.name())) {
                            evidenceEntries.add("\u5de5\u5177\uff1a" + response.name() + System.lineSeparator() + "\u7ed3\u679c\uff1a" + result);
                        }
                        log.info("[AI][REACT][OBSERVATION] traceId={}, step={}, tool={}, result={}",
                                traceId, step, response.name(), result);
                    });
        }

        //从消息列表里找工具结果
        private Stream<ToolResponseMessage.ToolResponse> toolResponses(List<Message> messages) {
            return messages.stream()
                    //只保留工具响应消息
                    .filter(ToolResponseMessage.class::isInstance)
                    //把普通 Message 转成：ToolResponseMessage
                    .map(ToolResponseMessage.class::cast)
                    //把一个ToolResponseMessage中可能包含的多个工具结果拆开
                    .flatMap(message -> message.getResponses().stream());
        }

        //多轮调用的输入输出token总和
        private void addUsage(Usage usage) {
            if (usage != null) {
                promptTokens += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                completionTokens += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }
        }

        private long elapsedMillis() {
            return (System.nanoTime() - startedAtNanos) / 1_000_000;
        }

       //给工具结果生成唯一标识：为了工具结果去重
        private String key(ToolResponseMessage.ToolResponse response) {
            return StringUtils.hasText(response.id())
                    ? response.id()
                    //工具没ID就用：工具名 + 工具结果的 hashCode例如：query_current_weather:8327183
                    : response.name() + ':' + String.valueOf(response.responseData()).hashCode();
        }
    }
}
