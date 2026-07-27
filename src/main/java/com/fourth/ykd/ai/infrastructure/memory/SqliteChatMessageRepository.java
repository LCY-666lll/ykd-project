package com.fourth.ykd.ai.infrastructure.memory;

import com.fourth.ykd.ai.dto.PersistedChatMessage;
import com.fourth.ykd.ai.dto.PersistedChatMessage.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天消息 SQLite 仓储类
 * 使用 Spring JdbcTemplate 进行数据库读写操作
 * 负责 chat_message 表的 CRUD 操作，支持软删除
 *
 * 软删除策略：
 * - 删除操作不物理删除，而是更新 deleted_at 字段为当前时间
 * - 查询操作只返回 deleted_at IS NULL 的记录
 * - 超出窗口大小的历史消息也使用软删除
 *
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SqliteChatMessageRepository {


    // Spring JDBC 模板，用于执行 SQL 语句
    // Spring Boot 自动配置，无需手动创建

    private final JdbcTemplate jdbcTemplate;

    private static final BeanPropertyRowMapper<PersistedChatMessage> CHAT_MESSAGE_ROW_MAPPER =
            new BeanPropertyRowMapper<>(PersistedChatMessage.class);

    /**
     * 插入消息 SQL
     * 插入 conversation_id、role、content、created_at 四个字段
     * created_at 由 save() 方法显式传入 LocalDateTime.now()（本地时间）
     * deleted_at 默认值为 NULL（未删除状态）
     */
    private static final String INSERT_SQL = """
            INSERT INTO chat_message (conversation_id, role, content,created_at)
            VALUES (?, ?, ?,?)
            """;

    /**
     * 查询所有活跃消息 SQL（按时间正序）
     * 条件：deleted_at IS NULL（只查询未删除的消息）
     */
    private static final String SELECT_ALL_ACTIVE_BY_CONVERSATION_ID = """
            SELECT id, conversation_id, role, content, created_at, deleted_at
            FROM chat_message
            WHERE conversation_id = ? AND deleted_at IS NULL
            ORDER BY created_at ASC
            """;

    /**
     * 软删除 SQL
     * 将 deleted_at 更新为当前时间，标记为已删除
     * 用户说"清除我的记忆"时调用此方法
     */
    private static final String SOFT_DELETE_BY_CONVERSATION_ID = """
            UPDATE chat_message
            SET deleted_at = ?
            WHERE conversation_id = ? AND deleted_at IS NULL
            """;

    /**
     * 清理超出窗口大小历史消息的 SQL
     * 将超出窗口的旧消息标记为软删除
     * 保留最新的 limit 条消息
     */
    private static final String CLEANUP_EXCESS_MESSAGES = """
            UPDATE chat_message
            SET deleted_at = ?
            WHERE conversation_id = ?
            AND deleted_at IS NULL
            AND id NOT IN (
                SELECT id FROM chat_message
                WHERE conversation_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC
                LIMIT ?
            )
            """;


    // 保存一条用户问题或机器人回复
    public void save(String conversationId, Role role, String content) {
        // 验证参数合法性
        validate(conversationId, role, content);

        // 使用 JdbcTemplate 执行 INSERT SQL
        // 占位符顺序：? → conversationId, ? → role.name(), ? → content
        jdbcTemplate.update(
                INSERT_SQL,
                conversationId.trim(),   // 会话 ID（去除首尾空格）
                role.name(),             // 角色名（枚举转字符串，如 "USER"）
                content.trim(),          // 消息内容（去除首尾空格）
                LocalDateTime.now()      // 显式传入 Java 本地时间（使用系统默认时区即 Asia/Shanghai）
        );

        // 记录调试日志
        log.debug("[SQLite][CHAT_MESSAGE_SAVED] conversationId={}, role={}", conversationId, role);
    }

    // 查询某个会话的所有活跃消息（按时间正序）

    public List<PersistedChatMessage> findAllActiveByConversationId(String conversationId) {
        // 参数校验：会话 ID 不能为空
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }

        // 使用 JdbcTemplate 执行查询
        return jdbcTemplate.query(
                SELECT_ALL_ACTIVE_BY_CONVERSATION_ID,
                CHAT_MESSAGE_ROW_MAPPER,
                conversationId.trim()
        );
    }

    /**
     * 软删除：根据会话 ID 标记所有消息为已删除
     * 用户说"清除我的记忆"时调用此方法
     */
    public void softDeleteByConversationId(String conversationId) {
        // 参数校验：会话 ID 不能为空
        if (!StringUtils.hasText(conversationId)) {
            return;
        }

        // 执行 UPDATE 语句，将 deleted_at 设为当前时间
        int updated = jdbcTemplate.update(SOFT_DELETE_BY_CONVERSATION_ID,
                LocalDateTime.now(),          // deleted_at 使用 Java 本地时间
                conversationId.trim());       // conversation_id

        // 记录被更新的行数
        log.debug("[SQLite][CHAT_MESSAGES_SOFT_DELETED] conversationId={}, count={}", conversationId, updated);
    }

    /**
     * 清理超出窗口大小的历史消息（软删除）
     * 保留最新的 maxMessages 条消息，旧消息标记为已删除
     */
    public void cleanupExcessMessages(String conversationId, int maxMessages) {
        // 参数校验：会话 ID 不能为空，窗口大小必须大于 0
        if (!StringUtils.hasText(conversationId) || maxMessages <= 0) {
            return;
        }

        // 执行 UPDATE 语句，将超出窗口的旧消息标记为删除
        int deleted = jdbcTemplate.update(
                CLEANUP_EXCESS_MESSAGES,
                LocalDateTime.now(),          // deleted_at 使用 Java 本地时间
                conversationId.trim(),        // 会话 ID（外层 WHERE）
                conversationId.trim(),        // 会话 ID（子查询 WHERE）
                maxMessages                   // 保留数量
        );

        // 如果有记录被删除，记录日志
        if (deleted > 0) {
            log.debug("[SQLite][EXCESS_MESSAGES_CLEANED] conversationId={}, count={}", conversationId, deleted);
        }
    }


    // 验证参数合法性

    private void validate(String conversationId, Role role, String content) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }
}