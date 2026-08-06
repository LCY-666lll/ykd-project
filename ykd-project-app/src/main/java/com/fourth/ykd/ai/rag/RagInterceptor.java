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
 * RAG 质量监控拦截器。
 *
 * <p>实现 BaseAdvisor（Spring AI 的 Hook/Interceptor 接口），
 * 在模型调用前后执行质量监控。
 *
 * <p>职责：
 * <ul>
 *   <li>BEFORE：记录用户查询长度 + 超长消息告警</li>
 *   <li>AFTER：空回答检测 + 异常短回答告警</li>
 * </ul>
 */
@Slf4j
@Component
public class RagInterceptor implements BaseAdvisor {

    /** 超长输入告警阈值（字符数）。超过此值只记录日志，不阻断请求。 */
    private static final int MAX_INPUT_LENGTH = 5000;

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

        // 超长消息告警（不阻断，只记录日志便于排查）
        if (userQuery.length() > MAX_INPUT_LENGTH) {
            log.warn("[RAG][INTERCEPTOR][LONG_INPUT] length={}, preview={}",
                    userQuery.length(), preview(userQuery, 50));
        }

        log.debug("[RAG][INTERCEPTOR][BEFORE] queryLength={}", userQuery.length());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (response == null || response.chatResponse() == null) {
            log.warn("[RAG][INTERCEPTOR][AFTER][EMPTY_RESPONSE] 模型返回了空响应");
            return response;
        }

        String answer = null;
        try {
            var result = response.chatResponse().getResult();
            if (result != null && result.getOutput() != null) {
                answer = result.getOutput().getText();
            }
        } catch (Exception e) {
            log.debug("[RAG][INTERCEPTOR][AFTER] 无法提取回答内容, reason={}", e.getMessage());
        }

        int answerLen = answer != null ? answer.length() : 0;

        if (answerLen == 0) {
            // 空回答可能是模型拒绝回答或 RAG 检索失败导致
            log.warn("[RAG][INTERCEPTOR][AFTER][EMPTY_ANSWER] 模型返回了空回答，"
                    + "可能原因：检索失败 / 模型拒绝 / 系统异常");
        } else if (answerLen < 10) {
            log.info("[RAG][INTERCEPTOR][AFTER][SHORT_ANSWER] answerLen={}, preview={}",
                    answerLen, answer);
        } else {
            log.debug("[RAG][INTERCEPTOR][AFTER] answerLen={}", answerLen);
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

    /** 截断文本用于日志输出。 */
    private static String preview(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}