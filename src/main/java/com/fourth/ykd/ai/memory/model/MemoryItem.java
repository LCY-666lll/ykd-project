package com.fourth.ykd.ai.memory.model;

import java.time.LocalDateTime;

/**
 * 已经保存到 SQLite 中的 长期记忆。
 * 该对象与 agent_memory 表中的一条记录对应。
 */
public record MemoryItem(

        /**
         * 记忆唯一标识，使用 UUID。
         */
        String id,

        /**
         * 记忆所属用户。
         * 当前项目中使用微信用户 ID，
         * 用于保证不同用户之间的记忆隔离。
         */
        String userId,

        /**
         * 记忆类型。
         */
        MemoryType type,

        /**
         * 记忆的稳定业务键。
         * 没有稳定键的追加型记忆可以为空。
         */
        String memoryKey,

        /**
         * 完整记忆内容。
         */
        String content,

        /**
         * 用于检索和模型注入的简短摘要。
         */
        String summary,

        /**
         * 记忆重要程度，取值范围为 0 到 1。
         */
        double importance,

        /**
         * 记忆可信程度，取值范围为 0 到 1。
         */
        double confidence,

        /**
         * 记忆当前状态。
         */
        MemoryStatus status,

        /**
         * 产生这条记忆的会话 ID。
         */
        String sourceConversationId,

        /**
         * 规范化内容经过哈希计算后的结果。
         * 用于识别内容完全相同的重复记忆。
         */
        String contentHash,

        /**
         * 当前记录替代的旧记忆 ID。
         * 如果不是替代产生的新版本，则为空。
         */
        String supersedesId,

        /**
         * 记忆过期时间。
         * 永久有效时为空。
         */
        LocalDateTime expiresAt,

        /**
         * 记忆创建时间。
         */
        LocalDateTime createdAt,

        /**
         * 记忆最后更新时间。
         */
        LocalDateTime updatedAt,

        /**
         * 记忆最后一次被检索使用的时间。
         */
        LocalDateTime lastAccessedAt,

        /**
         * 记忆累计被检索使用的次数。
         */
        long accessCount
) {
}