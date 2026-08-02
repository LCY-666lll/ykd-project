package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.index.RedisMemoryVectorIndex;
import com.fourth.ykd.ai.memory.model.MemoryIndexOutboxTask;
import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import com.fourth.ykd.ai.memory.repository.MemoryIndexOutboxRepository;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立处理 Redis 长期记忆索引同步任务。
 * Redis 异常只会延迟任务，不会影响 SQLite、聊天或短期记忆。
 */
@Slf4j
@Service
public class MemoryIndexOutboxProcessor {

    private static final int BATCH_SIZE = 20;
    private static final long RETRY_DELAY_SECONDS = 30L;

    private final MemoryIndexOutboxRepository outboxRepository;
    private final SqliteLongTermMemoryRepository memoryRepository;
    private final ObjectProvider<RedisMemoryVectorIndex> vectorIndexProvider;
    private final Executor memoryIndexExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MemoryIndexOutboxProcessor(
            MemoryIndexOutboxRepository outboxRepository,
            SqliteLongTermMemoryRepository memoryRepository,
            ObjectProvider<RedisMemoryVectorIndex> vectorIndexProvider,
            @Qualifier("memoryIndexExecutor") Executor memoryIndexExecutor
    ) {
        this.outboxRepository = outboxRepository;
        this.memoryRepository = memoryRepository;
        this.vectorIndexProvider = vectorIndexProvider;
        this.memoryIndexExecutor = memoryIndexExecutor;
    }

    @Scheduled(fixedDelay = 30_000)
    public void scheduleDueTasks() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            memoryIndexExecutor.execute(() -> {
                try {
                    processDueTasks();
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            running.set(false);
            log.warn("[AI][MEMORY_INDEX][SCHEDULE_REJECTED]");
        }
    }

    private void processDueTasks() {
        for (MemoryIndexOutboxTask task : outboxRepository.findDueTasks(BATCH_SIZE)) {
            processTask(task);
        }
    }

    private void processTask(MemoryIndexOutboxTask task) {
        try {
            RedisMemoryVectorIndex vectorIndex = vectorIndexProvider.getIfAvailable();
            if (vectorIndex == null) {
                throw new IllegalStateException("Redis 长期记忆索引不可用");
            }
            if (task.operation() == MemoryIndexOutboxTask.Operation.DELETE) {
                vectorIndex.deleteByMemoryId(task.memoryId());
            } else {
                syncUpsert(vectorIndex, task.memoryId());
            }
            outboxRepository.markDone(task.id());
        } catch (RuntimeException exception) {
            outboxRepository.markRetry(
                    task.id(),
                    RETRY_DELAY_SECONDS,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            log.warn("[AI][MEMORY_INDEX][RETRY] taskId={}, memoryId={}, operation={}, reason={}",
                    task.id(), task.memoryId(), task.operation(), exception.getMessage());
        }
    }

    private void syncUpsert(RedisMemoryVectorIndex vectorIndex, String memoryId) {
        MemoryItem memoryItem = memoryRepository.findById(memoryId).orElse(null);
        if (memoryItem == null
                || memoryItem.status() != MemoryStatus.ACTIVE
                || (memoryItem.expiresAt() != null
                && !memoryItem.expiresAt().isAfter(LocalDateTime.now()))) {
            vectorIndex.deleteByMemoryId(memoryId);
            return;
        }
        vectorIndex.upsert(memoryItem);
    }
}
