package com.fourth.ykd.ai.memory.index;

import com.fourth.ykd.ai.memory.repository.MemoryIndexOutboxRepository;
import com.fourth.ykd.ai.memory.service.MemoryIndexOutboxProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 SQLite Outbox 能将临时长期记忆同步到真实 Redis 测试索引。 */
@SpringBootTest(properties = {
        "ilink.enabled=false",
        "spring.main.web-application-type=none",
        "spring.ai.vectorstore.redis.initialize-schema=true",
        "spring.ai.vectorstore.redis.index-name=ykd-agent-memory-test-index",
        "spring.ai.vectorstore.redis.prefix=ykd:agent-memory:test:"
})
@EnabledIfEnvironmentVariable(named = "REDIS_VECTOR_INTEGRATION_TEST", matches = "true")
class MemoryIndexOutboxRedisIntegrationTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryIndexOutboxRepository outboxRepository;
    @Autowired private MemoryIndexOutboxProcessor processor;
    @Autowired private RedisMemoryVectorIndex vectorIndex;

    @Test
    void shouldSynchronizePendingOutboxTaskToRedis() {
        String id = "outbox-it-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO agent_memory (id,user_id,memory_type,memory_key,content,summary,importance,confidence,status,content_hash,created_at,updated_at,access_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", id, "outbox-it-user", "TASK", "outbox." + id, "Outbox Redis 集成测试记忆", "Outbox Redis 集成测试记忆", 0.8, 0.9, "ACTIVE", "hash." + id, Timestamp.valueOf(now), Timestamp.valueOf(now), 0);
        outboxRepository.enqueue(id, com.fourth.ykd.ai.memory.model.MemoryIndexOutboxTask.Operation.UPSERT);
        try {
            processor.scheduleDueTasks();
            for (int i = 0; i < 20; i++) {
                Integer done = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_index_outbox WHERE memory_id = ? AND status = 'DONE'", Integer.class, id);
                if (done != null && done == 1) break;
                Thread.sleep(250);
            }
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_index_outbox WHERE memory_id = ? AND status = 'DONE'", Integer.class, id)).isEqualTo(1);
            assertThat(vectorIndex.searchActiveMemoryIds("outbox-it-user", "Outbox Redis 集成测试记忆", 5)).contains(id);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            vectorIndex.deleteByMemoryId(id);
            jdbcTemplate.update("DELETE FROM memory_index_outbox WHERE memory_id = ?", id);
            jdbcTemplate.update("DELETE FROM agent_memory WHERE id = ?", id);
        }
    }
}