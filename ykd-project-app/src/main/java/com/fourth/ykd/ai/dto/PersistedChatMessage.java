package com.fourth.ykd.ai.dto;

/**
 * SQLite 中一条已持久化的聊天消息。
 */
public record PersistedChatMessage(
        long id,
        String conversationId,
        Role role,
        String content
) {

    public enum Role {
        USER,
        ASSISTANT
    }
}