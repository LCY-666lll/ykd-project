package com.fourth.ykd.ai.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fourth.ykd.ai.dto.PersistedChatMessage;
import com.fourth.ykd.ai.dto.PersistedChatMessage.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SqliteChatMessageRepositoryTest {

    @Test
    void shouldSaveQueryAndSoftDeleteChatMessages() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource("jdbc:sqlite:data/ykd-reply-memory.db")
        );

        SqliteChatMessageRepository repository =
                new SqliteChatMessageRepository(jdbcTemplate);

        String conversationId = "sqlite-test-" + UUID.randomUUID();

        try {
            repository.save(conversationId, Role.USER, "你好");
            repository.save(conversationId, Role.ASSISTANT, "你好，请问有什么可以帮助你？");

            List<PersistedChatMessage> messages =
                    repository.findRecentActive(conversationId, 20);

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).role()).isEqualTo(Role.USER);
            assertThat(messages.get(0).content()).isEqualTo("你好");
            assertThat(messages.get(1).role()).isEqualTo(Role.ASSISTANT);

            int deletedCount = repository.softDeleteByConversationId(conversationId);

            assertThat(deletedCount).isEqualTo(2);
            assertThat(repository.findRecentActive(conversationId, 20)).isEmpty();
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM chat_message WHERE conversation_id = ?",
                    conversationId
            );
        }
    }

    @Test
    void shouldKeepOnlyLatestHundredActiveMessages() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource("jdbc:sqlite:data/ykd-reply-memory.db")
        );
        SqliteChatMessageRepository repository =
                new SqliteChatMessageRepository(jdbcTemplate);
        String conversationId = "sqlite-window-test-" + UUID.randomUUID();

        try {
            for (int index = 1; index <= 101; index++) {
                repository.save(conversationId, Role.USER, "message-" + index);
            }

            int deletedCount = repository.softDeleteOldMessages(conversationId, 100);
            List<PersistedChatMessage> messages =
                    repository.findRecentActive(conversationId, 200);

            assertThat(deletedCount).isEqualTo(1);
            assertThat(messages).hasSize(100);
            assertThat(messages.get(0).content()).isEqualTo("message-2");
            assertThat(messages.get(99).content()).isEqualTo("message-101");
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM chat_message WHERE conversation_id = ?",
                    conversationId
            );
        }
    }}