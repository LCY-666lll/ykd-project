package com.fourth.ykd.ai.memory.repository;

import com.fourth.ykd.ai.memory.model.MemoryIndexOutboxTask;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * memory_index_outbox 表对应的 数据库操作层，不负责真正调用 Redis
 * Redis 长期记忆索引同步任务仓库。
 * 只读写 memory_index_outbox，不直接调用 Redis。
 */
@Repository
@RequiredArgsConstructor
public class MemoryIndexOutboxRepository {

    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;

    /** 在 SQLite 事务内新增一条待同步任务。 */
    public void enqueue(
            String memoryId,
            MemoryIndexOutboxTask.Operation operation
    ) {
        if (!StringUtils.hasText(memoryId) || operation == null) {
            throw new IllegalArgumentException("索引同步任务参数不能为空");
        }

        jdbcTemplate.update("""
                INSERT INTO memory_index_outbox (
                    memory_id,
                    operation,
                    status,
                    retry_count,
                    next_attempt_at,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, 'PENDING', 0, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                memoryId.trim(),
                operation.name()
        );
    }

    /** 读取当前到期、尚未完成的任务。 */
    public List<MemoryIndexOutboxTask> findDueTasks(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("任务查询数量必须大于 0");
        }

        return jdbcTemplate.query("""
                SELECT
                    id,
                    memory_id,
                    operation,
                    status,
                    retry_count,
                    next_attempt_at,
                    last_error,
                    created_at,
                    updated_at,
                    completed_at
                FROM memory_index_outbox
                WHERE status = 'PENDING'
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY next_attempt_at, id
                LIMIT ?
                """,
                //查询结果转换成 Java 对象
                (resultSet, rowNum) -> new MemoryIndexOutboxTask(
                        resultSet.getLong("id"),
                        resultSet.getString("memory_id"),
                        MemoryIndexOutboxTask.Operation.valueOf(
                                resultSet.getString("operation")
                        ),
                        MemoryIndexOutboxTask.Status.valueOf(
                                resultSet.getString("status")
                        ),
                        resultSet.getInt("retry_count"),
                        toLocalDateTime(resultSet.getTimestamp("next_attempt_at")),
                        resultSet.getString("last_error"),
                        toLocalDateTime(resultSet.getTimestamp("created_at")),
                        toLocalDateTime(resultSet.getTimestamp("updated_at")),
                        toLocalDateTime(resultSet.getTimestamp("completed_at"))
                ),
                limit
        );
    }

    /** Redis 同步成功后标记任务完成。 */
    public void markDone(long taskId) {
        requireTaskId(taskId);
        jdbcTemplate.update("""
                UPDATE memory_index_outbox
                SET status = 'DONE',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP,
                    last_error = NULL
                WHERE id = ?
                  AND status = 'PENDING'
                """,
                taskId
        );
    }

    /** Redis 同步失败后保留任务，等待下一轮重试。 */
    public void markRetry(
            long taskId,
            long retryDelaySeconds,
            String lastError
    ) {
        requireTaskId(taskId);
        if (retryDelaySeconds <= 0) {
            throw new IllegalArgumentException("重试延迟必须大于 0");
        }
        jdbcTemplate.update("""
                UPDATE memory_index_outbox
                SET retry_count = retry_count + 1,
                    next_attempt_at = datetime('now', ?),
                    last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'PENDING'
                """,
                "+" + retryDelaySeconds + " seconds",
                normalizeError(lastError),
                taskId
        );
    }

    private static void requireTaskId(long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("任务 ID 必须大于 0");
        }
    }
    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    //防止整段巨大异常堆栈直接塞进 SQLite last_error。
    private static String normalizeError(String value) {
        if (!StringUtils.hasText(value)) {
            return "未知 Redis 同步异常";
        }
        //去掉换行符
        String normalized = value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();

        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }
}