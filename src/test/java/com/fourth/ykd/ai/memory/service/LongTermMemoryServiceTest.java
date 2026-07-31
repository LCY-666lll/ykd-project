package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.model.MemoryCandidate;
import com.fourth.ykd.ai.memory.model.MemoryConsolidationResult;
import com.fourth.ykd.ai.memory.model.MemoryOperation;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import com.fourth.ykd.ai.memory.model.MemoryType;
import com.fourth.ykd.ai.memory.model.MemoryWriteResult;
import com.fourth.ykd.ai.memory.policy.MemoryCandidatePolicy;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用内存 SQLite 验证长期记忆完整写入流程。
 * 测试不会读写项目真实的 ykd-memory.db。
 */
@SpringJUnitConfig(LongTermMemoryServiceTest.TestConfiguration.class)
class LongTermMemoryServiceTest {

    @Autowired
    private LongTermMemoryService memoryService;

    @Autowired
    private SqliteLongTermMemoryRepository memoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 每个测试开始前重新创建干净的长期记忆表，
     * 避免不同测试之间的数据互相影响。
     */
    @BeforeEach
    void createMemoryTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS agent_memory");

        jdbcTemplate.execute("""
                CREATE TABLE agent_memory (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    memory_type TEXT NOT NULL,
                    memory_key TEXT,
                    content TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    importance REAL NOT NULL,
                    confidence REAL NOT NULL,
                    status TEXT NOT NULL,
                    source_conversation_id TEXT,
                    content_hash TEXT NOT NULL,
                    supersedes_id TEXT,
                    expires_at DATETIME,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    last_accessed_at DATETIME,
                    access_count INTEGER NOT NULL DEFAULT 0
                )
                """);
    }

    /**
     * 验证创建、重复确认、版本替换、用户隔离和删除。
     */
    @Test
    void shouldCompleteTheFullMemoryWriteLifecycle() {
        MemoryWriteResult created = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                branchCandidate("master"),
                decision(MemoryConsolidationResult.Action.CREATE)
        );
        assertThat(created.action()).isEqualTo(MemoryWriteResult.Action.CREATED);

        // 再次写入相同事实时，只确认原记忆，不创建重复记录。
        MemoryWriteResult confirmed = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                branchCandidate("master"),
                decision(
                        MemoryConsolidationResult.Action.CONFIRM,
                        created.memory().id()
                )
        );
        assertThat(confirmed.action()).isEqualTo(MemoryWriteResult.Action.CONFIRMED);
        assertThat(memoryRepository.findActiveByUserId("user-1", 10)).hasSize(1);

        // 同一个 key 出现新内容时，新记忆替代旧版本。
        MemoryWriteResult replaced = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                branchCandidate("lcy-project"),
                decision(
                        MemoryConsolidationResult.Action.REPLACE,
                        created.memory().id()
                )
        );
        assertThat(replaced.action()).isEqualTo(MemoryWriteResult.Action.REPLACED);
        assertThat(replaced.memory().supersedesId()).isEqualTo(created.memory().id());
        assertThat(memoryRepository.findById(created.memory().id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.SUPERSEDED);

        // 相同的记忆 key 在不同用户之间必须完全隔离。
        MemoryWriteResult otherUser = memoryService.applyDecision(
                "user-2",
                "conversation-2",
                branchCandidate("master"),
                decision(MemoryConsolidationResult.Action.CREATE)
        );
        assertThat(memoryRepository.findActiveByUserId("user-2", 10))
                .extracting(memory -> memory.id())
                .containsExactly(otherUser.memory().id());

        // 删除 user-1 的记忆时，不能影响 user-2。
        MemoryWriteResult deleted = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                deleteBranchCandidate(),
                decision(
                        MemoryConsolidationResult.Action.DELETE,
                        replaced.memory().id()
                )
        );
        assertThat(deleted.action()).isEqualTo(MemoryWriteResult.Action.DELETED);
        assertThat(memoryRepository.findActiveByUserId("user-1", 10)).isEmpty();
        assertThat(memoryRepository.findActiveByUserId("user-2", 10))
                .extracting(memory -> memory.id())
                .containsExactly(otherUser.memory().id());
    }
    /**
     * 验证新版本插入失败时，旧版本仍保持有效。
     */
    @Test
    void shouldRollbackOldVersionWhenNewVersionInsertFails() {
        MemoryWriteResult created = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                branchCandidate("master"),
                decision(MemoryConsolidationResult.Action.CREATE)
        );

        // 测试触发器用于模拟新版本写入 SQLite 失败。
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_memory_insert
                BEFORE INSERT ON agent_memory
                WHEN NEW.content LIKE '%触发失败%'
                BEGIN
                    SELECT RAISE(ABORT, 'forced failure');
                END
                """);

        assertThatThrownBy(() -> memoryService.applyDecision(
                "user-1",
                "conversation-1",
                branchCandidate("触发失败"),
                decision(
                        MemoryConsolidationResult.Action.REPLACE,
                        created.memory().id()
                )
        )).isInstanceOf(RuntimeException.class);

        // 事务必须回滚旧记录的 SUPERSEDED 状态。
        assertThat(memoryRepository.findById(created.memory().id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.ACTIVE);
    }
    @Test
    void shouldConsolidateDifferentKeysAndDeleteByMemoryId() {
        MemoryCandidate hangzhou = new MemoryCandidate(
                MemoryType.PREFERENCE,
                "preference.weather.default_location",
                "用户未指定城市时默认查询杭州天气。",
                "默认天气城市为杭州",
                0.8,
                0.95,
                MemoryOperation.UPSERT,
                null
        );
        MemoryCandidate zhengzhou = new MemoryCandidate(
                MemoryType.PREFERENCE,
                "preference.default_weather_city",
                "用户未指定城市时默认查询郑州天气。",
                "默认天气城市为郑州",
                0.8,
                0.95,
                MemoryOperation.UPSERT,
                null
        );

        MemoryWriteResult first = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                hangzhou,
                new MemoryConsolidationResult.Decision(
                        0,
                        MemoryConsolidationResult.Action.CREATE,
                        List.of()
                )
        );
        MemoryWriteResult second = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                zhengzhou,
                new MemoryConsolidationResult.Decision(
                        1,
                        MemoryConsolidationResult.Action.CREATE,
                        List.of()
                )
        );

        MemoryWriteResult replaced = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                hangzhou,
                new MemoryConsolidationResult.Decision(
                        0,
                        MemoryConsolidationResult.Action.REPLACE,
                        List.of(
                                first.memory().id(),
                                second.memory().id()
                        )
                )
        );

        assertThat(memoryRepository.findActiveByUserId("user-1", 10))
                .extracting(memory -> memory.id())
                .containsExactly(replaced.memory().id());
        assertThat(memoryRepository.findById(first.memory().id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.SUPERSEDED);
        assertThat(memoryRepository.findById(second.memory().id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.SUPERSEDED);

        MemoryCandidate deleteWithoutKey = new MemoryCandidate(
                MemoryType.PREFERENCE,
                null,
                null,
                null,
                0,
                0,
                MemoryOperation.DELETE,
                null
        );

        memoryService.applyDecision(
                "user-1",
                "conversation-1",
                deleteWithoutKey,
                new MemoryConsolidationResult.Decision(
                        0,
                        MemoryConsolidationResult.Action.DELETE,
                        List.of(replaced.memory().id())
                )
        );

        assertThat(memoryRepository.findActiveByUserId("user-1", 10))
                .isEmpty();
        assertThat(memoryRepository.findById(replaced.memory().id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.DELETED);
    }
    @Test
    void shouldReplaceMisclassifiedProfileWithPreference() {
        MemoryCandidate wrongProfile = new MemoryCandidate(
                MemoryType.PROFILE,
                "profile.default_weather_city",
                "用户默认查询天气的城市是郑州。",
                "默认天气城市为郑州",
                0.7,
                1.0,
                MemoryOperation.UPSERT,
                null
        );
        MemoryCandidate correctPreference = new MemoryCandidate(
                MemoryType.PREFERENCE,
                "preference.default_weather_city",
                "用户默认天气查询城市为杭州。",
                "默认天气城市为杭州",
                0.8,
                1.0,
                MemoryOperation.UPSERT,
                null
        );

        MemoryWriteResult created = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                wrongProfile,
                decision(MemoryConsolidationResult.Action.CREATE)
        );

        assertThatThrownBy(() -> memoryService.applyDecision(
                "user-1",
                "conversation-1",
                correctPreference,
                decision(
                        MemoryConsolidationResult.Action.CONFIRM,
                        created.memory().id()
                )
        )).isInstanceOf(IllegalStateException.class);

        MemoryWriteResult replaced = memoryService.applyDecision(
                "user-1",
                "conversation-1",
                correctPreference,
                decision(
                        MemoryConsolidationResult.Action.REPLACE,
                        created.memory().id()
                )
        );

        assertThat(memoryRepository.findById(created.memory().id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.SUPERSEDED);
        assertThat(memoryRepository.findActiveByUserId("user-1", 10))
                .singleElement()
                .satisfies(memory -> {
                    assertThat(memory.id()).isEqualTo(replaced.memory().id());
                    assertThat(memory.type()).isEqualTo(MemoryType.PREFERENCE);
                    assertThat(memory.content()).contains("杭州");
                });
    }

    private MemoryConsolidationResult.Decision decision(
            MemoryConsolidationResult.Action action,
            String... targetMemoryIds
    ) {
        return new MemoryConsolidationResult.Decision(
                0,
                action,
                List.of(targetMemoryIds)
        );
    }
    /**
     * 模拟以后 AI 提取出的项目分支候选记忆。
     */
    private MemoryCandidate branchCandidate(String branch) {
        return new MemoryCandidate(
                MemoryType.PROJECT,
                "project.ykd.active_branch",
                "ykd-project 当前分支是 " + branch,
                "当前开发分支是 " + branch,
                0.9,
                0.95,
                MemoryOperation.UPSERT,
                null
        );
    }

    private MemoryCandidate deleteBranchCandidate() {
        return new MemoryCandidate(
                MemoryType.PROJECT,
                "project.ykd.active_branch",
                null,
                null,
                0,
                0,
                MemoryOperation.DELETE,
                null
        );
    }

    /**
     * 只装配本测试需要的 Bean，不启动 AI、iLink 或真实数据库。
     */
    @Configuration
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            return new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MemoryCandidatePolicy memoryCandidatePolicy() {
            return new MemoryCandidatePolicy();
        }

        @Bean
        SqliteLongTermMemoryRepository memoryRepository(JdbcTemplate jdbcTemplate) {
            return new SqliteLongTermMemoryRepository(jdbcTemplate);
        }

        @Bean
        LongTermMemoryService memoryService(
                MemoryCandidatePolicy candidatePolicy,
                SqliteLongTermMemoryRepository memoryRepository
        ) {
            return new LongTermMemoryService(candidatePolicy, memoryRepository);
        }
    }
}
