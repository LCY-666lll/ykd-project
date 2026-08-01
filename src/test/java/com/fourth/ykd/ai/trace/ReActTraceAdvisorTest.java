package com.fourth.ykd.ai.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.test.util.ReflectionTestUtils;

class ReActTraceAdvisorTest {

    @Test
    void shouldUseExplicitToolRoundLimitForBrowserStyleTasks() {
        ReActTraceAdvisor advisor = new ReActTraceAdvisor(
                mock(ToolCallingManager.class),
                1,
                mock(ChatMemory.class),
                mock(SqliteChatMessageRepository.class),
                16
        );

        assertThat(ReflectionTestUtils.getField(advisor, "maxToolRounds")).isEqualTo(16);
    }
}
