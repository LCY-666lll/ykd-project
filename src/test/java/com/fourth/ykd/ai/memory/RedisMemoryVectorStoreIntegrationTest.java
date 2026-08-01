package com.fourth.ykd.ai.memory;

import com.fourth.ykd.ai.memory.index.RedisMemoryUserScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 DashScope Embedding、Redis Stack 向量索引和用户隔离能够协同工作。
 *
 * 该测试只验证独立向量基础设施，
 * 不接入正式长期记忆读取、写入和微信聊天链路。
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
class RedisMemoryVectorStoreIntegrationTest {

    // Spring AI 注入项目自定义的 Redis VectorStore。
    @Autowired
    private VectorStore vectorStore;

    /**
     * 写入两个用户的相似记忆，
     * 验证语义检索只能返回过滤条件指定用户的记忆。
     */
    @Test
    void shouldRetrieveMemoryForCurrentUserOnly() {
        String currentUserId = "redis-test-user@example.com";
        String anotherUserId = "another-redis-test-user@example.com";
        String currentUserScope =
                RedisMemoryUserScope.fromUserId(currentUserId);

        String currentUserMemoryId =
                "redis-current-user-" + UUID.randomUUID();

        String anotherUserMemoryId =
                "redis-another-user-" + UUID.randomUUID();

        long updatedAt = System.currentTimeMillis();

        Document currentUserMemory = createMemoryDocument(
                currentUserMemoryId,
                currentUserId,
                "用户计划本周六上午整理杭州家庭旅行相册。",
                updatedAt
        );

        Document anotherUserMemory = createMemoryDocument(
                anotherUserMemoryId,
                anotherUserId,
                "用户计划本周六上午整理上海家庭旅行相册。",
                updatedAt
        );

        vectorStore.add(List.of(
                currentUserMemory,
                anotherUserMemory
        ));

        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query("用户周六准备处理什么事情？")
                    .topK(10)
                    .similarityThresholdAll()
                    .filterExpression(
                            "userScope == '" + currentUserScope + "'"
                    )
                    .build();

            List<Document> results =
                    vectorStore.similaritySearch(searchRequest);

            assertTrue(
                    results.stream().anyMatch(document ->
                            currentUserMemoryId.equals(document.getId()))
            );

            assertFalse(
                    results.stream().anyMatch(document ->
                            anotherUserMemoryId.equals(document.getId()))
            );

            assertTrue(
                    results.stream().allMatch(document ->
                            currentUserId.equals(
                                    document.getMetadata().get("userId")
                            ))
            );
        } finally {
            // 测试结束后删除两个用户的临时向量记忆。
            vectorStore.delete(List.of(
                    currentUserMemoryId,
                    anotherUserMemoryId
            ));
        }
    }

    /**
     * 创建一条带有长期记忆元数据的测试文档。
     *
     * @param memoryId 记忆 ID
     * @param userId 微信用户 ID
     * @param content 记忆文本
     * @param updatedAt 更新时间戳
     * @return 可以写入 Redis VectorStore 的测试文档
     */
    private Document createMemoryDocument(
            String memoryId,
            String userId,
            String content,
            long updatedAt
    ) {
        return new Document(
                memoryId,
                content,
                Map.of(
                        "userId", userId,
                        "userScope", RedisMemoryUserScope.fromUserId(userId),
                        "memoryId", memoryId,
                        "memoryType", "TASK",
                        "memoryKey", "task.family-photo",
                        "status", "ACTIVE",
                        "importance", 0.8,
                        "confidence", 0.95,
                        "updatedAt", updatedAt
                )
        );
    }
}