package com.fourth.ykd.ai.infrastructure.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

/**
 * 软删除聊天消息。
 *
 * <p>当用户在微信中发送精确短语"软删除记忆"时，由 {@code IlinkReplyProcessor}
 * 直接调用本类的 {@link #execute(String)} 方法，不走 AI 路由和工具调用。
 *
 * <p>执行内容：
 * <ol>
 *   <li>SQLite：将当前用户 chat_message 表中所有有效记录的 deleted_at 置为当前时间</li>
 *   <li>内存：清除 ChatMemory 中的会话上下文</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoftDeleteChatMessage {

    private final SqliteChatMessageRepository sqliteChatMessageRepository;
    private final ChatMemory chatMemory;

    /**
     * 软删除指定用户的所有聊天记忆。
     *
     * @param userId 用户微信 ID（即 conversationId）
     * @return 执行结果描述（含删除条数）
     */
    public String execute(String userId) {
        if (userId == null || userId.isBlank()) {
            log.warn("[记忆][软删除][失败] 用户ID为空");
            return "软删除失败：无法获取用户信息，请稍后重试。";
        }

        try {
            int deletedCount = sqliteChatMessageRepository.softDeleteByConversationId(userId);
            chatMemory.clear(userId);

            log.info("[记忆][软删除][成功] userId={}, deletedCount={}", userId, deletedCount);
            return String.format(
                    "聊天记忆已软删除，共清除 %d 条消息，上下文记忆已重置。",
                    deletedCount);
        } catch (Exception e) {
            log.warn("[记忆][软删除][异常] userId={}, reason={}", userId, e.getMessage());
            return "软删除上下文记忆时发生异常，请稍后重试。";
        }
    }
}
