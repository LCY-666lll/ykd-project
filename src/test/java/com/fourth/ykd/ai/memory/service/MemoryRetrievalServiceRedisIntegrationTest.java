package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.index.RedisMemoryVectorIndex;
import com.fourth.ykd.ai.memory.model.MemoryIndexOutboxTask;
import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.repository.MemoryIndexOutboxRepository;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Redis 语义召回、SQLite 二次校验和正式长期记忆读取服务的完整链路。
 */
@SpringBootTest(properties = {
        "ilink.enabled=false",
        "spring.main.web-application-type=none",
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.vectorstore.redis.initialize-schema=true",
        "spring.ai.vectorstore.redis.index-name=ykd-agent-memory-test-index",
        "spring.ai.vectorstore.redis.prefix=ykd:agent-memory:test:"
})
@EnabledIfEnvironmentVariable(
        named = "REDIS_VECTOR_INTEGRATION_TEST",
        matches = "true"
)
class MemoryRetrievalServiceRedisIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqliteLongTermMemoryRepository memoryRepository;

    @Autowired
    private MemoryIndexOutboxRepository outboxRepository;

    @Autowired
    private MemoryIndexOutboxProcessor outboxProcessor;

    @Autowired
    private MemoryRetrievalService memoryRetrievalService;

    @Autowired
    private RedisMemoryVectorIndex vectorIndex;

    /**
     * 验证语义相关记忆会优先返回，
     * 并且 SQLite 删除后 Redis 中残留的旧索引不能重新进入长期记忆上下文。
     */
    @Test
    void shouldRetrieveSemanticMemoryAndRejectStaleRedisDocument() {
        String userId = "memory-retrieval-it-user";
        String semanticMemoryId = "semantic-memory-" + UUID.randomUUID();
        String fallbackMemoryId = "fallback-memory-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        insertMemory(
                semanticMemoryId,
                userId,
                "用户下周六上午要整理杭州家庭旅行相册。",
                "用户下周六上午要整理杭州家庭旅行相册。",
                0.7,
                now
        );
        insertMemory(
                fallbackMemoryId,
                userId,
                "用户喜欢简洁直接的回答风格。",
                "用户喜欢简洁直接的回答风格。",
                0.95,
                now
        );

        outboxRepository.enqueue(
                semanticMemoryId,
                MemoryIndexOutboxTask.Operation.UPSERT
        );
        outboxRepository.enqueue(
                fallbackMemoryId,
                MemoryIndexOutboxTask.Operation.UPSERT
        );

        try {
            outboxProcessor.scheduleDueTasks();

            waitUntilOutboxDone(semanticMemoryId);
            waitUntilOutboxDone(fallbackMemoryId);

            List<MemoryItem> retrievedMemories =
                    memoryRetrievalService.retrieve(
                            userId,
                            "我下周六上午有什么安排？"
                    );

            assertThat(retrievedMemories)
                    .extracting(MemoryItem::id)
                    .startsWith(semanticMemoryId)
                    .contains(fallbackMemoryId);

            memoryRepository.markDeleted(semanticMemoryId);

            List<MemoryItem> afterDeleteMemories =
                    memoryRetrievalService.retrieve(
                            userId,
                            "我下周六上午有什么安排？"
                    );

            assertThat(afterDeleteMemories)
                    .extracting(MemoryItem::id)
                    .doesNotContain(semanticMemoryId)
                    .contains(fallbackMemoryId);
        } finally {
            vectorIndex.deleteByMemoryId(semanticMemoryId);
            vectorIndex.deleteByMemoryId(fallbackMemoryId);

            jdbcTemplate.update(
                    "DELETE FROM memory_index_outbox WHERE memory_id IN (?, ?)",
                    semanticMemoryId,
                    fallbackMemoryId
            );
            jdbcTemplate.update(
                    "DELETE FROM agent_memory WHERE id IN (?, ?)",
                    semanticMemoryId,
                    fallbackMemoryId
            );
        }
    }

    /**
     * 插入一条用于真实集成测试的 SQLite 长期记忆。
     */
    private void insertMemory(
            String memoryId,
            String userId,
            String content,
            String summary,
            double importance,
            LocalDateTime now
    ) {
        jdbcTemplate.update("""
                INSERT INTO agent_memory (
                    id,
                    user_id,
                    memory_type,
                    memory_key,
                    content,
                    summary,
                    importance,
                    confidence,
                    status,
                    content_hash,
                    created_at,
                    updated_at,
                    access_count
                )
                VALUES (?, ?, 'TASK', ?, ?, ?, ?, 0.95, 'ACTIVE', ?, ?, ?, 0)
                """,
                memoryId,
                userId,
                "task." + memoryId,
                content,
                summary,
                importance,
                "hash." + memoryId,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)
        );
    }

    /**
     * 等待 Outbox 后台同步任务完成。
     */
    private void waitUntilOutboxDone(String memoryId) {
        for (int index = 0; index < 20; index++) {
            Integer doneCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM memory_index_outbox
                    WHERE memory_id = ?
                      AND status = 'DONE'
                    """,
                    Integer.class,
                    memoryId
            );

            if (doneCount != null && doneCount == 1) {
                return;
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "等待 Redis 索引同步时被中断",
                        exception
                );
            }
        }

        throw new IllegalStateException(
                "等待 Redis 索引同步超时，memoryId=" + memoryId
        );
    }
}