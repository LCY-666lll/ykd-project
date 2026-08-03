package com.fourth.ykd.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG Interceptor — 输入验证 + 输出质量监控。
 *
 * <p>对应 Hooks 官方文档「ModelInterceptor」模式。
 * 实现 BaseAdvisor，在 RagHook(order=5) 之后执行(order=6)。
 *
 * <p>职责：
 * <ul>
 *   <li>BEFORE：超长消息拦截 + 日志记录</li>
 *   <li>AFTER：空回答检测 + 异常短回答告警 + 性能日志</li>
 * </ul>
 */
@Slf4j
@Component
public class RagInterceptor implements BaseAdvisor {

    private final int maxInputLength;

    public RagInterceptor(RagVectorStoreConfig config) {
        this.maxInputLength = config.getMaxInputLength();
    }

    @Override
    public String getName() {
        return "rag_interceptor";
    }

    @Override
    public int getOrder() {
        return 6;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Prompt prompt = request.prompt();
        if (prompt == null) {
            return request;
        }

        String userQuery = extractUserText(prompt);
        if (userQuery.isEmpty()) {
            return request;
        }

        // 超长消息拦截
        if (userQuery.length() > maxInputLength) {
            log.warn("[RAG][INTERCEPTOR][REJECTED] reason=input_too_long, length={}, "
                    + "preview={}", userQuery.length(), preview(userQuery, 50));
            return ChatClientRequest.builder()
                    .prompt(prompt.augmentSystemMessage(
                            "注意：用户消息过长（" + userQuery.length()
                            + " 字符），请提醒用户精简后重新发送。"))
                    .context(request.context())
                    .build();
        }

        log.info("[RAG][INTERCEPTOR][BEFORE] queryLength={}", userQuery.length());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (response == null || response.chatResponse() == null) {
            log.warn("[RAG][INTERCEPTOR][AFTER] empty_response");
            return response;
        }

        String answer = null;
        try {
            var result = response.chatResponse().getResult();
            if (result != null && result.getOutput() != null) {
                answer = result.getOutput().getText();
            }
        } catch (Exception e) {
            log.debug("[RAG][INTERCEPTOR][AFTER] 无法提取回答内容, reason={}",
                    e.getMessage());
        }

        int answerLen = answer != null ? answer.length() : 0;

        // 空回答告警
        if (answerLen == 0) {
            log.warn("[RAG][INTERCEPTOR][AFTER][EMPTY_ANSWER] 模型返回了空回答");
        }
        // 异常短回答告警（正常回答通常 > 10 字符）
        else if (answerLen < 10) {
            log.info("[RAG][INTERCEPTOR][AFTER][SHORT_ANSWER] answerLen={}, "
                    + "preview={}", answerLen, answer);
        } else {
            log.info("[RAG][INTERCEPTOR][AFTER] answerLen={}", answerLen);
        }

        return response;
    }

    // ==================== 内部方法 ====================

    /** 从 Prompt 中提取最后一条 UserMessage 的文本。 */
    private static String extractUserText(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage msg) {
                return msg.getText();
            }
        }
        return "";
    }

    private static String preview(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}