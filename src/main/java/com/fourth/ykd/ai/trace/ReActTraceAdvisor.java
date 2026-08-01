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

/** 仅记录既有工具循环，不参与模型决策或改变模型返回。 */
@Slf4j
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

    public ReActTraceAdvisor(ToolCallingManager toolCallingManager, int advisorOrder,
            ChatMemory chatMemory, SqliteChatMessageRepository sqliteChatMessageRepository, int maxToolRounds) {
        super(toolCallingManager, advisorOrder);
        if (maxToolRounds < 1) {
            throw new IllegalArgumentException("maxToolRounds must be positive");
        }
        this.chatMemory = chatMemory;
        this.sqliteChatMessageRepository = sqliteChatMessageRepository;
        this.maxToolRounds = maxToolRounds;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Map<String, Object> context = new HashMap<>(request.context());
        context.put(TRACE_CONTEXT_KEY, new TraceState(resolveConversationId(context)));
        return super.adviseCall(request.mutate().context(context).build(), chain);
    }

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        TraceState trace = traceState(request.context());
        if (trace != null) {
            trace.markKnownObservations(request.prompt().getInstructions());
            log.info("[AI][REACT][START] traceId={}, conversationId={}", trace.traceId, trace.conversationId);
        }
        return request;
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        TraceState trace = traceState(request.context());
        if (trace != null) {
            trace.logNewObservations(request.prompt().getInstructions());
        }
        return request;
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        TraceState trace = traceState(response.context());
        ChatResponse chatResponse = response.chatResponse();
        if (trace == null || chatResponse == null || chatResponse.getResult() == null) {
            return response;
        }
        trace.step++;
        trace.addUsage(chatResponse.getMetadata() == null ? null : chatResponse.getMetadata().getUsage());
        AssistantMessage output = chatResponse.getResult().getOutput();
        if (output instanceof DeepSeekAssistantMessage deepSeekMessage
                && StringUtils.hasText(deepSeekMessage.getReasoningContent())) {
            trace.reasoningPresent = true;
            log.info("[AI][REACT][THOUGHT] traceId={}, step={}, reasoning={}", trace.traceId, trace.step,
                    sanitize(deepSeekMessage.getReasoningContent()));
        }
        List<AssistantMessage.ToolCall> toolCalls =
                output == null ? List.of() : output.getToolCalls();
        if (!toolCalls.isEmpty()) {
            if (trace.toolRounds >= maxToolRounds) {
                log.warn("[AI][REACT][STOP] traceId={}, toolRounds={}, reason=MAX_TOOL_ROUNDS",
                        trace.traceId, trace.toolRounds);
                throw new BusinessException(50007, "本次任务需要的工具调用过多，已停止执行，请拆分请求后重试");
            }
            trace.toolRounds++;
        }
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            log.info("[AI][REACT][ACTION] traceId={}, step={}, tool={}, arguments={}", trace.traceId, trace.step,
                    toolCall.name(), sanitize(toolCall.arguments()));
        }        return response;
    }

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

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = SENSITIVE_VALUE_PATTERN.matcher(value.replaceAll("[\\r\\n]+", " ").trim())
                .replaceAll("$1\"***\"");
        return sanitized.length() <= MAX_LOG_LENGTH ? sanitized : sanitized.substring(0, MAX_LOG_LENGTH) + "...";
    }

    private static String resolveConversationId(Map<String, Object> context) {
        Object conversationId = context.get(ChatMemory.CONVERSATION_ID);
        return conversationId == null ? "default" : String.valueOf(conversationId);
    }

    private void saveToolEvidenceMemory(TraceState trace) {
        if (trace.evidenceEntries.isEmpty()) {
            return;
        }
        String content = """
                \u3010\u5de5\u5177\u4f9d\u636e\u8bb0\u5fc6\u3011
                \u672c\u8f6e\u81ea\u52a8\u8c03\u7528\u5de5\u5177\u53d6\u5f97\u4ee5\u4e0b\u7ed3\u679c\u3002\u540e\u7eed\u7528\u6237\u8ffd\u95ee\u521a\u624d\u7ed3\u679c\u3001\u4f9d\u636e\u3001\u6765\u6e90\u6216\u5df2\u751f\u6210\u5185\u5bb9\u65f6\u4f18\u5148\u53c2\u8003\uff1b\u7528\u6237\u660e\u786e\u8981\u6c42\u91cd\u65b0\u67e5\u8be2\u5b9e\u65f6\u4fe1\u606f\u65f6\u624d\u91cd\u65b0\u8c03\u7528\u5de5\u5177\u3002
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

    private static String trimEvidence(String value) {
        return value.length() <= MAX_EVIDENCE_MEMORY_LENGTH
                ? value
                : value.substring(0, MAX_EVIDENCE_MEMORY_LENGTH) + "...";
    }

    private static boolean isEvidenceTool(String toolName) {
        return Set.of("search_realtime_information", "query_current_weather", "query_weather_forecast",
                "get_time_info", "translate_text", "calculate_math_expression").contains(toolName);
    }


    private static TraceState traceState(Map<String, Object> context) {
        Object trace = context.get(TRACE_CONTEXT_KEY);
        return trace instanceof TraceState traceState ? traceState : null;
    }

    private static final class TraceState {
        private final String traceId = UUID.randomUUID().toString().substring(0, 8);
        private final String conversationId;
        private final long startedAtNanos = System.nanoTime();
        private final Set<String> loggedObservations = new HashSet<>();
        private final List<String> evidenceEntries = new ArrayList<>();
        private int step;
        private int toolRounds;
        private int promptTokens;
        private int completionTokens;
        private boolean reasoningPresent;

        private TraceState(String conversationId) {
            this.conversationId = conversationId;
        }

        private void markKnownObservations(List<Message> messages) {
            toolResponses(messages).forEach(response -> loggedObservations.add(key(response)));
        }

        private void logNewObservations(List<Message> messages) {
            toolResponses(messages)
                    .filter(response -> loggedObservations.add(key(response)))
                    .forEach(response -> {
                        String result = sanitize(response.responseData());
                        if (isEvidenceTool(response.name())) {
                            evidenceEntries.add("\u5de5\u5177\uff1a" + response.name() + System.lineSeparator() + "\u7ed3\u679c\uff1a" + result);
                        }
                        log.info("[AI][REACT][OBSERVATION] traceId={}, step={}, tool={}, result={}",
                                traceId, step, response.name(), result);
                    });
        }

        private java.util.stream.Stream<ToolResponseMessage.ToolResponse> toolResponses(List<Message> messages) {
            return messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream());
        }

        private void addUsage(Usage usage) {
            if (usage != null) {
                promptTokens += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                completionTokens += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }
        }

        private long elapsedMillis() {
            return (System.nanoTime() - startedAtNanos) / 1_000_000;
        }

        private String key(ToolResponseMessage.ToolResponse response) {
            return StringUtils.hasText(response.id())
                    ? response.id()
                    : response.name() + ':' + String.valueOf(response.responseData()).hashCode();
        }
    }
}
