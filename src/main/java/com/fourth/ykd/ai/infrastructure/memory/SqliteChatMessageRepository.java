package com.fourth.ykd.ai.infrastructure.memory;

import com.fourth.ykd.ai.dto.PersistedChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;


@Repository
public class SqliteChatMessageRepository {
    private JdbcTemplate jdbcTemplate;
    public SqliteChatMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    //Create
    private static final String INSERT_SQL = """
            INSERT INTO chat_message
            (conversation_id,role,content)
            VALUES(?,?,?)""";

    public void save(String conversation_id, String role, String content) {
        //trim()是用来去除字符串前后的空格
        jdbcTemplate.update(INSERT_SQL, conversation_id.trim(), role, content.trim());
    }

    //Read
    private static final String SELECT_BY_CONVERSATION_SQL = """
            SELECT
            id,
            conversation_id,
            role,
            content,
            created_at,
            deleted_at
            FROM chat_message
            WHERE conversation_id=?
            AND deleted_at IS NULL
            ORDER BY id ASC""";

    public List<PersistedChatMessage> findByConversationId(String conversationId) {
        return jdbcTemplate.query(
                SELECT_BY_CONVERSATION_SQL,
                (rs, rowNum) -> {

                    return new PersistedChatMessage(
                            rs.getLong("id"),
                            rs.getString("conversation_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("created_at"),
                            rs.getString("deleted_at")
                    );
                },
                conversationId
        );
    }

    //Update相当于删除因为是软删除，把deleted_at的值等于当前时间即可，然后由值的就代表在那一时刻被删除
    private static final String UPDATE_SQL = """
            UPDATE
            chat_message
            SET deleted_at=CURRENT_TIMESTAMP
            WHERE conversationId=?""";

    public void update(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId不能为空");
        }
        jdbcTemplate.update(
                UPDATE_SQL,
                conversationId
        );
    }


}



