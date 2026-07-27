package com.fourth.ykd.ai.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 聊天消息持久化 DTO - 数据库字段映射
 *
 * 软删除规则：
 * - deleted_at 为 NULL → 这条聊天记忆仍有效
 * - deleted_at 为具体时间→ 这条聊天记忆已在该时间删除
 */
@Data  // Lombok 自动生成 getter、setter、equals、hashCode、toString
@NoArgsConstructor  // Lombok 自动生成无参构造函数
public class PersistedChatMessage {

    /**
     * 主键 ID（自增）
     * 对应数据库字段：id INTEGER PRIMARY KEY AUTOINCREMENT
     */
    private Long id;

    /**
     * 会话 ID（用户 ID）
     * 对应数据库字段：conversation_id TEXT NOT NULL
     * 用于区分不同用户的聊天记录，iLink 场景下使用微信 userId
     */
    private String conversationId;

    /**
     * 角色枚举：USER（用户）/ ASSISTANT（AI）
     * 对应数据库字段：role TEXT NOT NULL
     */
    private Role role;

    /**
     * 消息内容
     * 对应数据库字段：content TEXT NOT NULL
     */
    private String content;

    /**
     * 创建时间
     * 对应数据库字段：created_at TEXT NOT NULL
     * 由 save() 方法显式传入 LocalDateTime.now()（本地时间）
     */
    private LocalDateTime createdAt;

    /**
     * 删除时间（软删除标记）
     * 对应数据库字段：deleted_at TEXT
     * NULL → 未删除（有效）
     * 非 NULL → 已删除（无效）
     */
    private LocalDateTime deletedAt;

    /**
     * 角色枚举
     * USER：用户发送的消息
     * ASSISTANT：AI 回复的消息
     */
    public enum Role {
        USER,
        ASSISTANT
    }
}