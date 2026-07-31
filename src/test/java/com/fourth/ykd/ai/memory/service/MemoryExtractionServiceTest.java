package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.model.MemoryCandidate;
import com.fourth.ykd.ai.memory.model.MemoryExtractionResult;
import com.fourth.ykd.ai.memory.model.MemoryOperation;
import com.fourth.ykd.ai.memory.model.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ??????????????????????
 * ?????? ChatClient???????????
 */
class MemoryExtractionServiceTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private MemoryExtractionService memoryExtractionService;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(builder.build()).thenReturn(chatClient);
        memoryExtractionService = new MemoryExtractionService(builder);
    }

    @Test
    void shouldReturnCandidatesWhenStructuredOutputIsValid() {
        MemoryCandidate candidate = preferenceCandidate("??????");
        whenStructuredResult().thenReturn(new MemoryExtractionResult(List.of(candidate)));

        List<MemoryCandidate> result = memoryExtractionService.extract(
                "????????",
                "??????????????"
        );

        assertThat(result).containsExactly(candidate);
    }

    @Test
    void shouldRetryWhenFirstStructuredConversionFails() {
        MemoryCandidate candidate = preferenceCandidate("????????");
        whenStructuredResult()
                .thenThrow(new IllegalArgumentException("????????????"))
                .thenReturn(new MemoryExtractionResult(List.of(candidate)));

        List<MemoryCandidate> result = memoryExtractionService.extract(
                "???????????",
                "???????????????"
        );

        assertThat(result).containsExactly(candidate);
    }

    @Test
    void shouldReturnEmptyListWhenBothAttemptsFail() {
        whenStructuredResult()
                .thenThrow(new IllegalArgumentException("???????"))
                .thenThrow(new IllegalArgumentException("?????????"));

        List<MemoryCandidate> result = memoryExtractionService.extract(
                "????????",
                "???"
        );

        assertThat(result).isEmpty();
    }

    @Test
    void shouldIgnoreNullElementsAndKeepAtMostFiveCandidates() {
        List<MemoryCandidate> candidates = new ArrayList<>();
        candidates.add(null);
        for (int index = 1; index <= 6; index++) {
            candidates.add(preferenceCandidate("??" + index));
        }
        whenStructuredResult().thenReturn(new MemoryExtractionResult(candidates));

        List<MemoryCandidate> result = memoryExtractionService.extract(
                "???????????",
                "???"
        );

        assertThat(result)
                .hasSize(5)
                .doesNotContainNull();
    }

    @Test
    void shouldIncludeRecentConversationForReferenceResolution() {
        whenStructuredResult().thenReturn(new MemoryExtractionResult(List.of()));

        memoryExtractionService.extract(
                "取消它",
                "",
                "用户：我正在整理杭州旅行攻略"
        );

        verify(requestSpec).user(argThat((String prompt) ->
                prompt.contains("我正在整理杭州旅行攻略")
                        && prompt.contains("取消它")
        ));
    }
    @Test
    void shouldReturnEmptyListWhenUserMessageIsBlank() {
        List<MemoryCandidate> result = memoryExtractionService.extract("   ", "????");

        assertThat(result).isEmpty();
    }

    private OngoingStubbing<MemoryExtractionResult> whenStructuredResult() {
        return when(
                chatClient.prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .entity(MemoryExtractionResult.class)
        );
    }

    private MemoryCandidate preferenceCandidate(String content) {
        return new MemoryCandidate(
                MemoryType.PREFERENCE,
                "preference.answer_style",
                content,
                content,
                0.8,
                0.95,
                MemoryOperation.UPSERT,
                null
        );
    }
}
