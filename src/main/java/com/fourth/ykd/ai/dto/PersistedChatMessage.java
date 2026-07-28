package com.fourth.ykd.ai.dto;

public record PersistedChatMessage(Long id,String conversationId,String role,String content,String createdAt,String deleteAt) {


}
