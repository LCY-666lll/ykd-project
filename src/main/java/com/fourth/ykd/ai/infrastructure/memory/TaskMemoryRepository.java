package com.fourth.ykd.ai.infrastructure.memory;

import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * task_memory 表 SQLite 读写，统一管理周期任务和定时任务的持久化。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * <p>id——SQLite数据库中的序号</p>
     * <p>task_type——任务类型，PERIODIC（周期），SCHEDULED（定时）</p>
     * <p>task_name——任务名称，</p>
     * <p>user_id——用户微信ID</p>
     * <p>cron_expression——Cron 表达式（定时任务为 NULL）</p>
     * <p>task_description——用户的原始完整需求文本</p>
     * <p>status——任务状态，PENDING（等待执行），EXECUTED（已成功执行），CANCELLED（已取消），FAILED（执行过程中出错），EXPIRED（已过期）</p>
     * <p>execute_at——时间戳，任务应执行的时刻</p>
     * <p>last_executed_at——最近一次实际执行的时刻</p>
     */
    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS task_memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_type TEXT NOT NULL CHECK(task_type IN ('PERIODIC','SCHEDULED')),
                task_name TEXT NOT NULL,
                user_id TEXT NOT NULL,
                cron_expression TEXT,
                task_description TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','EXECUTED','CANCELLED','FAILED','EXPIRED')),
                execute_at INTEGER,
                last_executed_at TEXT
            )
            """;

    private static final String CREATE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_task_memory_type_status
            ON task_memory(task_type, status)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO task_memory (task_type, task_name, user_id, cron_expression, task_description, execute_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE task_memory SET status = ? WHERE id = ?
            """;

    private static final String UPDATE_LAST_EXECUTED_SQL = """
            UPDATE task_memory SET last_executed_at = ? WHERE id = ?
            """;

    private static final String FIND_PENDING_BY_TYPE_SQL = """
            SELECT * FROM task_memory WHERE task_type = ? AND status = 'PENDING' ORDER BY id
            """;

    private static final RowMapper<TaskRow> ROW_MAPPER = (rs, rowNum) -> new TaskRow(
            rs.getLong("id"),
            rs.getString("task_type"),
            rs.getString("task_name"),
            rs.getString("user_id"),
            rs.getString("cron_expression"),
            rs.getString("task_description"),
            rs.getString("status"),
            (Long) rs.getObject("execute_at"),
            rs.getString("last_executed_at")
    );

    @PostConstruct
    void initTable() {
        jdbcTemplate.execute(CREATE_TABLE_SQL);
        jdbcTemplate.execute(CREATE_INDEX_SQL);
        log.info("[task_memory] 表及索引初始化完成");
    }

    public long insert(String taskType, String taskName, String userId,
                       String cronExpression, String taskDescription,
                       Long executeAt) {
        jdbcTemplate.update(INSERT_SQL,
                taskType, taskName, userId, cronExpression, taskDescription, executeAt);
        Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        log.info("[task_memory][insert] id={}, type={}, name={}", id, taskType, taskName);
        return id != null ? id : 0;
    }

    public boolean updateStatus(long id, String status) {
        int rows = jdbcTemplate.update(UPDATE_STATUS_SQL, status, id);
        log.info("[task_memory][updateStatus] id={}, status={}, rows={}", id, status, rows);
        return rows > 0;
    }

    public boolean updateLastExecuted(long id, String time) {
        int rows = jdbcTemplate.update(UPDATE_LAST_EXECUTED_SQL, time, id);
        return rows > 0;
    }

    public List<TaskRow> findPendingByType(String taskType) {
        return jdbcTemplate.query(FIND_PENDING_BY_TYPE_SQL, ROW_MAPPER, taskType);
    }

    public record TaskRow(long id, String taskType, String taskName, String userId,
                          String cronExpression, String taskDescription, String status,
                          Long executeAt, String lastExecutedAt) {}
}
