package com.fourth.ykd.ai.memory.index;

import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import com.fourth.ykd.ai.memory.model.MemoryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Redis 长期记忆向量索引适配层。
 *
 * 该测试只验证 Redis 索引写入、用户隔离查询和删除，
 * 不接入正式 SQLite 写入、长期记忆读取和微信聊天链路。
 */
@SpringBootTest(properties = {
        "ilink.enabled=false",
        "spring.main.web-application-type=none",
        "spring.ai.vectorstore.redis.initialize-schema=true",
        "spring.ai.vectorstore.redis.index-name=ykd-agent-memory-test-index",
        "spring.ai.vectorstore.redis.prefix=ykd:agent-memory:test:"
})
@EnabledIfEnvironmentVariable(
        named = "REDIS_VECTOR_INTEGRATION_TEST",
        matches = "true"
)
class RedisMemoryVectorIndexIntegrationTest {

    // 注入待验证的 Redis 长期记忆索引适配层。
    @Autowired
    private RedisMemoryVectorIndex memoryVectorIndex;

    /**
     * 验证带特殊字符的用户 ID 仍只能召回自己的长期记忆。
     */
    @Test
    void shouldRetrieveMemoryIdForCurrentUserOnly() {
        String currentUserId = "redis-test-user@example.com";
        String anotherUserId = "another-redis-test-user@example.com";

        MemoryItem currentUserMemory = createMemoryItem(
                "redis-current-user-" + UUID.randomUUID(),
                currentUserId,
                "用户本周六上午要整理杭州家庭旅行相册。"
        );

        MemoryItem anotherUserMemory = createMemoryItem(
                "redis-another-user-" + UUID.randomUUID(),
                anotherUserId,
                "用户本周六上午要整理上海家庭旅行相册。"
        );

        memoryVectorIndex.upsert(currentUserMemory);
        memoryVectorIndex.upsert(anotherUserMemory);

        try {
            List<String> memoryIds =
                    memoryVectorIndex.searchActiveMemoryIds(
                            currentUserId,
                            "用户本周六上午要整理杭州家庭旅行相册。",
                            10
                    );

            assertTrue(memoryIds.contains(currentUserMemory.id()));
            assertFalse(memoryIds.contains(anotherUserMemory.id()));
        } finally {
            // 测试结束后删除两名用户的临时 Redis 向量文档。
            memoryVectorIndex.deleteByMemoryId(currentUserMemory.id());
            memoryVectorIndex.deleteByMemoryId(anotherUserMemory.id());
        }
    }

    /**
     * 验证删除 Redis 索引文档后，该 memoryId 不会再被语义召回。
     */
    @Test
    void shouldDeleteMemoryIdFromRedisVectorIndex() {
        String userId = "redis-delete-user@example.com";

        MemoryItem memoryItem = createMemoryItem(
                "redis-delete-user-" + UUID.randomUUID(),
                userId,
                "用户下周要完成家庭旅行照片整理。"
        );

        memoryVectorIndex.upsert(memoryItem);

        boolean deleted = false;

        try {
            List<String> beforeDelete =
                    memoryVectorIndex.searchActiveMemoryIds(
                            userId,
                            "用户下周要完成家庭旅行照片整理。",
                            10
                    );

            assertTrue(beforeDelete.contains(memoryItem.id()));

            memoryVectorIndex.deleteByMemoryId(memoryItem.id());
            deleted = true;

            List<String> afterDelete =
                    memoryVectorIndex.searchActiveMemoryIds(
                            userId,
                            "用户下周要完成家庭旅行照片整理。",
                            10
                    );

            assertFalse(afterDelete.contains(memoryItem.id()));
        } finally {
            // 删除前发生异常时，仍然清理测试临时文档。
            if (!deleted) {
                memoryVectorIndex.deleteByMemoryId(memoryItem.id());
            }
        }
    }

    /**
     * 创建一条模拟 SQLite 已持久化完成的长期记忆。
     *
     * @param memoryId 长期记忆 ID
     * @param userId 微信用户 ID
     * @param summary 用于 Redis 语义检索的摘要
     * @return 可以写入 Redis 向量索引的长期记忆
     */
    private MemoryItem createMemoryItem(
            String memoryId,
            String userId,
            String summary
    ) {
        LocalDateTime now = LocalDateTime.now();

        return new MemoryItem(
                memoryId,
                userId,
                MemoryType.TASK,
                "task.travel-album." + memoryId,
                summary,
                summary,
                0.8,
                0.95,
                MemoryStatus.ACTIVE,
                "redis-vector-test-conversation",
                "redis-vector-test-content-hash." + memoryId,
                null,
                null,
                now,
                now,
                null,
                0L
        );
    }
}
