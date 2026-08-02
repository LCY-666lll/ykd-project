package com.fourth.ykd.ai.memory.model;

import java.time.LocalDateTime;

/**
 * SQLite 中一条 Redis 长期记忆索引同步任务。
 * Redis 是可重建索引，任务失败不会修改 agent_memory 中的事实数据。
 */
public record MemoryIndexOutboxTask(
        long id,
        String memoryId,
        Operation operation,
        Status status,
        int retryCount,
        LocalDateTime nextAttemptAt,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt
) {

    /** Redis 索引需要执行的操作。 */
    public enum Operation {
        UPSERT,
        DELETE
    }

    /** 同步任务状态。 */
    public enum Status {
        PENDING,
        DONE
    }
}