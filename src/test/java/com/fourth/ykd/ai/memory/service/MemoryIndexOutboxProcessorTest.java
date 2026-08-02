package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.index.RedisMemoryVectorIndex;
import com.fourth.ykd.ai.memory.model.MemoryIndexOutboxTask;
import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import com.fourth.ykd.ai.memory.model.MemoryType;
import com.fourth.ykd.ai.memory.repository.MemoryIndexOutboxRepository;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Redis 索引同步器的成功与失败降级行为。
 * 所有依赖均为 Mock，不连接 Redis 或 SQLite。
 */
class MemoryIndexOutboxProcessorTest {

    @Test
    void shouldMarkTaskDoneAfterRedisUpsertSucceeds() {
        MemoryIndexOutboxRepository outboxRepository = mock(MemoryIndexOutboxRepository.class);
        SqliteLongTermMemoryRepository memoryRepository = mock(SqliteLongTermMemoryRepository.class);
        RedisMemoryVectorIndex vectorIndex = mock(RedisMemoryVectorIndex.class);
        ObjectProvider<RedisMemoryVectorIndex> provider = mock(ObjectProvider.class);
        MemoryIndexOutboxTask task = task(MemoryIndexOutboxTask.Operation.UPSERT);
        MemoryItem memoryItem = memoryItem();

        when(outboxRepository.findDueTasks(20)).thenReturn(List.of(task));
        when(provider.getIfAvailable()).thenReturn(vectorIndex);
        when(memoryRepository.findById(task.memoryId())).thenReturn(Optional.of(memoryItem));

        processor(outboxRepository, memoryRepository, provider).scheduleDueTasks();

        verify(vectorIndex).upsert(memoryItem);
        verify(outboxRepository).markDone(task.id());
    }

    @Test
    void shouldKeepTaskPendingWhenRedisUpsertFails() {
        MemoryIndexOutboxRepository outboxRepository = mock(MemoryIndexOutboxRepository.class);
        SqliteLongTermMemoryRepository memoryRepository = mock(SqliteLongTermMemoryRepository.class);
        RedisMemoryVectorIndex vectorIndex = mock(RedisMemoryVectorIndex.class);
        ObjectProvider<RedisMemoryVectorIndex> provider = mock(ObjectProvider.class);
        MemoryIndexOutboxTask task = task(MemoryIndexOutboxTask.Operation.UPSERT);
        MemoryItem memoryItem = memoryItem();

        when(outboxRepository.findDueTasks(20)).thenReturn(List.of(task));
        when(provider.getIfAvailable()).thenReturn(vectorIndex);
        when(memoryRepository.findById(task.memoryId())).thenReturn(Optional.of(memoryItem));
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(vectorIndex).upsert(memoryItem);

        processor(outboxRepository, memoryRepository, provider).scheduleDueTasks();

        verify(outboxRepository).markRetry(
                eq(task.id()),
                eq(30L),
                contains("Redis 不可用")
        );
    }

    private MemoryIndexOutboxProcessor processor(
            MemoryIndexOutboxRepository outboxRepository,
            SqliteLongTermMemoryRepository memoryRepository,
            ObjectProvider<RedisMemoryVectorIndex> provider
    ) {
        Executor directExecutor = Runnable::run;
        return new MemoryIndexOutboxProcessor(
                outboxRepository,
                memoryRepository,
                provider,
                directExecutor
        );
    }

    private MemoryIndexOutboxTask task(
            MemoryIndexOutboxTask.Operation operation
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new MemoryIndexOutboxTask(
                1L,
                "memory-1",
                operation,
                MemoryIndexOutboxTask.Status.PENDING,
                0,
                now,
                null,
                now,
                now,
                null
        );
    }

    private MemoryItem memoryItem() {
        LocalDateTime now = LocalDateTime.now();
        return new MemoryItem(
                "memory-1",
                "user-1",
                MemoryType.TASK,
                "task.example",
                "测试记忆",
                "测试记忆",
                0.8,
                0.9,
                MemoryStatus.ACTIVE,
                "conversation-1",
                "hash",
                null,
                null,
                now,
                now,
                null,
                0L
        );
    }
}