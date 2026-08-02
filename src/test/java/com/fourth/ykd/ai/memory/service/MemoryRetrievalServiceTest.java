package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.index.RedisMemoryVectorIndex;
import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import com.fourth.ykd.ai.memory.model.MemoryType;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证长期记忆检索服务的 SQLite 保底和 Redis 语义召回行为。
 */
class MemoryRetrievalServiceTest {

    @Test
    void shouldPreferRedisSemanticMemoriesAndKeepSqliteMemoriesAsFallback() {
        SqliteLongTermMemoryRepository repository = mock(SqliteLongTermMemoryRepository.class);
        RedisMemoryVectorIndex vectorIndex = mock(RedisMemoryVectorIndex.class);
        MemoryRetrievalService retrievalService = new MemoryRetrievalService(repository, providerOf(vectorIndex));
        MemoryItem sqliteFirst = memory("sqlite-first", "user-1");
        MemoryItem sqliteSecond = memory("sqlite-second", "user-1");
        MemoryItem semanticMemory = memory("semantic-memory", "user-1");

        when(repository.findActiveByUserId("user-1", 8)).thenReturn(List.of(sqliteFirst, sqliteSecond));
        when(vectorIndex.searchActiveMemoryIds("user-1", "我下周要做什么", 8))
                .thenReturn(List.of(semanticMemory.id(), sqliteFirst.id()));
        when(repository.findActiveByIdAndUserId("user-1", semanticMemory.id())).thenReturn(Optional.of(semanticMemory));
        when(repository.findActiveByIdAndUserId("user-1", sqliteFirst.id())).thenReturn(Optional.of(sqliteFirst));

        List<MemoryItem> actual = retrievalService.retrieve("user-1", "我下周要做什么");

        assertThat(actual).extracting(MemoryItem::id)
                .containsExactly(semanticMemory.id(), sqliteFirst.id(), sqliteSecond.id());
    }

    @Test
    void shouldKeepOriginalSqliteRetrievalWhenRedisFails() {
        SqliteLongTermMemoryRepository repository = mock(SqliteLongTermMemoryRepository.class);
        RedisMemoryVectorIndex vectorIndex = mock(RedisMemoryVectorIndex.class);
        MemoryRetrievalService retrievalService = new MemoryRetrievalService(repository, providerOf(vectorIndex));
        MemoryItem sqliteMemory = memory("sqlite-memory", "user-1");

        when(repository.findActiveByUserId("user-1", 8)).thenReturn(List.of(sqliteMemory));
        when(vectorIndex.searchActiveMemoryIds("user-1", "查询我的偏好", 8))
                .thenThrow(new IllegalStateException("Redis 暂时不可用"));

        List<MemoryItem> actual = retrievalService.retrieve("user-1", "查询我的偏好");

        assertThat(actual).containsExactly(sqliteMemory);
        verify(repository, never()).findActiveByIdAndUserId(anyString(), anyString());
    }

    @Test
    void shouldIgnoreRedisIdsThatAreNoLongerActiveInSqlite() {
        SqliteLongTermMemoryRepository repository = mock(SqliteLongTermMemoryRepository.class);
        RedisMemoryVectorIndex vectorIndex = mock(RedisMemoryVectorIndex.class);
        MemoryRetrievalService retrievalService = new MemoryRetrievalService(repository, providerOf(vectorIndex));
        MemoryItem activeMemory = memory("active-memory", "user-1");

        when(repository.findActiveByUserId("user-1", 8)).thenReturn(List.of());
        when(vectorIndex.searchActiveMemoryIds("user-1", "用户近期的任务", 8))
                .thenReturn(List.of("deleted-memory", "expired-memory", activeMemory.id()));
        when(repository.findActiveByIdAndUserId("user-1", "deleted-memory")).thenReturn(Optional.empty());
        when(repository.findActiveByIdAndUserId("user-1", "expired-memory")).thenReturn(Optional.empty());
        when(repository.findActiveByIdAndUserId("user-1", activeMemory.id())).thenReturn(Optional.of(activeMemory));

        List<MemoryItem> actual = retrievalService.retrieve("user-1", "用户近期的任务");

        assertThat(actual).containsExactly(activeMemory);
    }

    @Test
    void shouldUseSqliteOnlyWhenUserQueryIsEmpty() {
        SqliteLongTermMemoryRepository repository = mock(SqliteLongTermMemoryRepository.class);
        RedisMemoryVectorIndex vectorIndex = mock(RedisMemoryVectorIndex.class);
        MemoryRetrievalService retrievalService = new MemoryRetrievalService(repository, providerOf(vectorIndex));
        MemoryItem sqliteMemory = memory("sqlite-memory", "user-1");

        when(repository.findActiveByUserId("user-1", 8)).thenReturn(List.of(sqliteMemory));

        List<MemoryItem> actual = retrievalService.retrieve("user-1", " ");

        assertThat(actual).containsExactly(sqliteMemory);
        verify(vectorIndex, never()).searchActiveMemoryIds(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldReturnAtMostEightMemoriesAfterMerge() {
        SqliteLongTermMemoryRepository repository = mock(SqliteLongTermMemoryRepository.class);
        RedisMemoryVectorIndex vectorIndex = mock(RedisMemoryVectorIndex.class);
        MemoryRetrievalService retrievalService = new MemoryRetrievalService(repository, providerOf(vectorIndex));
        List<MemoryItem> sqliteMemories = List.of(
                memory("sqlite-1", "user-1"), memory("sqlite-2", "user-1"),
                memory("sqlite-3", "user-1"), memory("sqlite-4", "user-1"),
                memory("sqlite-5", "user-1"), memory("sqlite-6", "user-1"),
                memory("sqlite-7", "user-1"), memory("sqlite-8", "user-1")
        );
        MemoryItem semanticFirst = memory("semantic-1", "user-1");
        MemoryItem semanticSecond = memory("semantic-2", "user-1");

        when(repository.findActiveByUserId("user-1", 8)).thenReturn(sqliteMemories);
        when(vectorIndex.searchActiveMemoryIds("user-1", "语义检索问题", 8))
                .thenReturn(List.of(semanticFirst.id(), semanticSecond.id()));
        when(repository.findActiveByIdAndUserId("user-1", semanticFirst.id())).thenReturn(Optional.of(semanticFirst));
        when(repository.findActiveByIdAndUserId("user-1", semanticSecond.id())).thenReturn(Optional.of(semanticSecond));

        List<MemoryItem> actual = retrievalService.retrieve("user-1", "语义检索问题");

        assertThat(actual).hasSize(8);
        assertThat(actual).extracting(MemoryItem::id).startsWith(semanticFirst.id(), semanticSecond.id());
    }

    /**
     * 创建只返回指定 Redis 索引的 ObjectProvider。
     */
    private ObjectProvider<RedisMemoryVectorIndex> providerOf(RedisMemoryVectorIndex vectorIndex) {
        return new ObjectProvider<>() {
            @Override
            public RedisMemoryVectorIndex getObject() {
                return vectorIndex;
            }
        };
    }

    /**
     * 创建用于检索测试的有效长期记忆。
     */
    private MemoryItem memory(String memoryId, String userId) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 2, 12, 0);
        return new MemoryItem(
                memoryId, userId, MemoryType.TASK, "task." + memoryId,
                "测试长期记忆内容：" + memoryId, "测试长期记忆摘要：" + memoryId,
                0.8, 0.95, MemoryStatus.ACTIVE, "conversation-" + userId,
                "hash-" + memoryId, null, null, now, now, null, 0
        );
    }
}