package com.fourth.ykd.ai.infrastructure.memory;

import com.fourth.ykd.ai.dto.ReminderTask;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ReminderTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReminderTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS reminder_task (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    trigger_time_ms INTEGER,
                    cron_expression TEXT,
                    context_token TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
                )
                """);
    }

    private static final String INSERT_SQL = """
            INSERT INTO reminder_task (id, user_id, content, trigger_time_ms, cron_expression, context_token)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    public void save(ReminderTask task) {
        jdbcTemplate.update(INSERT_SQL,
                task.getId(),
                task.getUserId().trim(),
                task.getContent().trim(),
                task.getTriggerTimeMs(),
                task.getCronExpression(),
                task.getContextToken());
    }

    private static final String SELECT_ALL_SQL = """
            SELECT id, user_id, content, trigger_time_ms, cron_expression, context_token, created_at
            FROM reminder_task
            ORDER BY created_at ASC
            """;

    public List<ReminderTask> findAll() {
        return jdbcTemplate.query(SELECT_ALL_SQL, (rs, rowNum) -> {
            ReminderTask task = new ReminderTask();
            task.setId(rs.getString("id"));
            task.setUserId(rs.getString("user_id"));
            task.setContent(rs.getString("content"));
            long triggerTimeMs = rs.getLong("trigger_time_ms");
            task.setTriggerTimeMs(rs.wasNull() ? null : triggerTimeMs);
            task.setCronExpression(rs.getString("cron_expression"));
            task.setContextToken(rs.getString("context_token"));
            task.setCreatedAt(rs.getString("created_at"));
            return task;
        });
    }

    private static final String SELECT_BY_USER_SQL = """
            SELECT id, user_id, content, trigger_time_ms, cron_expression, context_token, created_at
            FROM reminder_task
            WHERE user_id = ?
            ORDER BY created_at ASC
            """;

    public List<ReminderTask> findByUserId(String userId) {
        return jdbcTemplate.query(SELECT_BY_USER_SQL,
                (rs, rowNum) -> {
                    ReminderTask task = new ReminderTask();
                    task.setId(rs.getString("id"));
                    task.setUserId(rs.getString("user_id"));
                    task.setContent(rs.getString("content"));
                    long triggerTimeMs = rs.getLong("trigger_time_ms");
                    task.setTriggerTimeMs(rs.wasNull() ? null : triggerTimeMs);
                    task.setCronExpression(rs.getString("cron_expression"));
                    task.setContextToken(rs.getString("context_token"));
                    task.setCreatedAt(rs.getString("created_at"));
                    return task;
                },
                userId);
    }

    private static final String DELETE_SQL = "DELETE FROM reminder_task WHERE id = ?";

    public void deleteById(String id) {
        jdbcTemplate.update(DELETE_SQL, id);
    }

    private static final String DELETE_BY_USER_SQL = "DELETE FROM reminder_task WHERE user_id = ?";

    public void deleteByUserId(String userId) {
        jdbcTemplate.update(DELETE_BY_USER_SQL, userId);
    }

    private static final String UPDATE_CONTEXT_TOKEN_SQL =
            "UPDATE reminder_task SET context_token = ? WHERE user_id = ?";

    public void updateContextToken(String userId, String contextToken) {
        jdbcTemplate.update(UPDATE_CONTEXT_TOKEN_SQL, contextToken, userId);
    }
}