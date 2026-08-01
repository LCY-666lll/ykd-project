package com.fourth.ykd.ai.memory.advisor;

import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import com.fourth.ykd.ai.memory.model.MemoryType;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import com.fourth.ykd.ai.memory.service.MemoryRetrievalService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryAdvisorSystemPromptTest {

    @Test
    void shouldPreserveOriginalSystemInstructionWhenMemoryIsInjected() {
        SqliteLongTermMemoryRepository repository = mock(SqliteLongTermMemoryRepository.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(new MemoryRetrievalService(repository), 100);
        ChatClientRequest request = requestFor("user-1", "ordinary question");

        when(repository.findActiveByUserId("user-1", 8)).thenReturn(List.of(memory()));
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(captor.capture());
        String systemText = captor.getValue().prompt().getSystemMessage().getText();
        assertThat(systemText)
                .contains("BASE_SYSTEM_INSTRUCTION")
                .contains("<long-term-memory>");
    }

    @Test
    void shouldSkipLongTermMemoryForCapabilityQuestion() {
        SqliteLongTermMemoryRepository repository = mock(SqliteLongTermMemoryRepository.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(new MemoryRetrievalService(repository), 100);
        ChatClientRequest request = requestFor("user-1", "\u4f60\u80fd\u5e72\u5565");

        when(chain.nextCall(request)).thenReturn(response);

        assertThat(advisor.adviseCall(request, chain)).isSameAs(response);
        verify(repository, never()).findActiveByUserId(anyString(), anyInt());
        verify(chain).nextCall(same(request));
    }

    private ChatClientRequest requestFor(String userId, String userQuery) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(
                        new SystemMessage("BASE_SYSTEM_INSTRUCTION"),
                        new UserMessage(userQuery)
                )))
                .context(ChatMemory.CONVERSATION_ID, userId)
                .build();
    }

    private MemoryItem memory() {
        LocalDateTime now = LocalDateTime.now();
        return new MemoryItem(
                "memory-1", "user-1", MemoryType.PREFERENCE, "answer_style", "concise",
                "concise", 0.8, 0.95, MemoryStatus.ACTIVE, "conversation-1", "hash-1",
                null, null, now, now, null, 0
        );
    }
}
