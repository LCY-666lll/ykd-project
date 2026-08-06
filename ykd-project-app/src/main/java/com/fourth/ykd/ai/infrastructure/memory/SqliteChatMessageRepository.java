package com.fourth.ykd.ai.infrastructure.memory;

import com.fourth.ykd.ai.dto.PersistedChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 负责 chat_message 表的 SQLite 读写,不负责调用大模型，也不负责决定何时保存消息。
 */
@Repository
@RequiredArgsConstructor
public class SqliteChatMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_SQL = """
            INSERT INTO chat_message (conversation_id, role, content)
            VALUES (?, ?, ?)
            """;

    /*作用：查询一个用户最近的、未删除的消息。
    假设调用：findRecentActive("user-001", 20);
    SQL 的含义是：只查 user-001→ 只查 deleted_at 为空的消息→ 按 id 从大到小排列→ 只拿最新x条*/
    private static final String FIND_RECENT_ACTIVE_SQL = """
            SELECT id, conversation_id, role, content
            FROM chat_message
            WHERE conversation_id = ?
              AND deleted_at IS NULL
            ORDER BY id DESC
            LIMIT ?
            """;

    private static final String SOFT_DELETE_SQL = """
            UPDATE chat_message
            SET deleted_at = CURRENT_TIMESTAMP
            WHERE conversation_id = ?
              AND deleted_at IS NULL
            """;

    /*保留这个用户 id 最新的 N 条有效消息
    → 其他有效消息更新 deleted_at
    → 变成软删除*/
    private static final String SOFT_DELETE_OLD_MESSAGES_SQL = """
        UPDATE chat_message
        SET deleted_at = CURRENT_TIMESTAMP
        WHERE conversation_id = ?
          AND deleted_at IS NULL
          AND id NOT IN (
              SELECT id
              FROM (
                  SELECT id
                  FROM chat_message
                  WHERE conversation_id = ?
                    AND deleted_at IS NULL
                  ORDER BY id DESC
                  LIMIT ?
              )
          )
        """;

    /*数据库中的一行聊天记录
        ↓
    读取各个字段
        ↓
    创建 PersistedChatMessage 对象

    resultSet：当前查询结果行，可以通过列名获取数据。
    rowNum：当前是查询结果的第几行，比如第 0 行、第 1 行。代码没有使用 rowNum，但方法规定必须接收它。*/
    private static final RowMapper<PersistedChatMessage> CHAT_MESSAGE_ROW_MAPPER =
            (resultSet, rowNum) -> new PersistedChatMessage(
                    resultSet.getLong("id"),
                    resultSet.getString("conversation_id"),
                    PersistedChatMessage.Role.valueOf(resultSet.getString("role")),
                    resultSet.getString("content")
            );

    /**
     * 保存一条用户问题或机器人回复。
     * @return 实际写入的行数，正常情况下为 1
     */
    public int save(String conversationId, PersistedChatMessage.Role role,String content){
        String normalizedConversationId = requireText(conversationId, "conversationId");
        if (role==null) {
            throw new IllegalArgumentException("role不能为空");
        }
        if (!StringUtils.hasText(content)) {
             throw new IllegalArgumentException("content 不能为空");
        }
        return jdbcTemplate.update(
                INSERT_SQL,
                normalizedConversationId,
                role.name(),
                content
        );
    }

    /**
     * 查询某个用户最近的有效消息，并按聊天发生顺序返回。
     */
    public List<PersistedChatMessage> findRecentActive(String conversationId, int limit) {
        String normalizedConversationId = requireText(conversationId, "conversationId");

        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }

        List<PersistedChatMessage> messages = new ArrayList<>(
                jdbcTemplate.query(
                        FIND_RECENT_ACTIVE_SQL,
                        CHAT_MESSAGE_ROW_MAPPER,
                        normalizedConversationId,
                        limit
                )
        );

        /*数据库倒序查询最近 N 条：5、4、3
               ↓
        Java 反转
               ↓
        正常聊天顺序：3、4、5*/
        Collections.reverse(messages);
        return messages;
    }


    /**
     * 软删除一个用户的所有有效记忆.
     * @return 本次被标记删除的消息数量
     */
    public int softDeleteByConversationId(String conversationId) {
        return jdbcTemplate.update(
                SOFT_DELETE_SQL,
                requireText(conversationId, "conversationId")
        );
    }

    /**
     * 保留某个用户最近的 maxMessages 条有效消息，
     * 将更早消息软删除。
     * @return 本次自动软删除的消息数量
     */
    public int softDeleteOldMessages(String conversationId, int maxMessages) {
        String normalizedConversationId = requireText(conversationId, "conversationId");

        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages 必须大于 0");
        }

        return jdbcTemplate.update(
                SOFT_DELETE_OLD_MESSAGES_SQL,
                normalizedConversationId,
                normalizedConversationId,
                maxMessages
        );
    }

    /*检查原始会话 ID，并获得去掉首尾空格后的标准会话 ID。
    value      → 真正需要检查的数据
    fieldName  → 这个数据的字段名称，用于拼接报错信息*/
    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }
}
