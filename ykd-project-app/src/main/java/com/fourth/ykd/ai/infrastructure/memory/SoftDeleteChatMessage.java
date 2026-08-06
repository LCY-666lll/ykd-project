package com.fourth.ykd.ai.infrastructure.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

/**
 * 软删除聊天记忆。
 *
 * <p>当用户在微信中发送精确短语"软删除记忆"时调用，
 * 不走 AI 路由和工具调用，直接执行以下操作：
 * <ol>
 *   <li>SQLite：将当前用户 chat_message 表中所有有效记录的
 *       deleted_at 置为当前时间</li>
 *   <li>内存：清除 ChatMemory 中的会话上下文</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoftDeleteChatMessage {

    private final SqliteChatMessageRepository sqliteChatMessageRepository;
    private final ChatMemory chatMemory;

    /** 触发软删除的精确短语（完全匹配，不含前后空格）。 */
    public static final String TRIGGER_PHRASE = "软删除记忆";

    /** 操作完成后的用户回复文本。 */
    public static final String SUCCESS_REPLY = "已清除您的会话记忆。";

    /**
     * 执行软删除操作。
     *
     * @param userId 用户ID（微信 conversationId）
     * @return 被软删除的消息数量
     */
    public int execute(String userId) {
        log.info("[MEMORY][SOFT_DELETE][START] userId={}", userId);

        // 1. SQLite：软删除 chat_message 表中该用户全部有效记录
        int deletedCount = sqliteChatMessageRepository.softDeleteByConversationId(userId);
        log.info("[MEMORY][SOFT_DELETE][SQLITE] userId={}, deletedCount={}",
                userId, deletedCount);

        // 2. 内存：清除 ChatMemory 中的会话上下文
        chatMemory.clear(userId);
        log.info("[MEMORY][SOFT_DELETE][MEMORY] userId={}, ChatMemory已清除", userId);

        log.info("[MEMORY][SOFT_DELETE][DONE] userId={}", userId);
        return deletedCount;
    }
}