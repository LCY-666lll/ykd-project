package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.model.MemoryCandidate;
import com.fourth.ykd.ai.memory.model.MemoryConsolidationResult;
import com.fourth.ykd.ai.memory.model.MemoryOperation;
import com.fourth.ykd.ai.memory.model.MemoryType;
import com.fourth.ykd.ai.memory.model.MemoryWriteResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryFormationServiceTest {

    @Test
    void shouldStopBeforeConsolidationWhenExtractionFails() {
        MemoryExtractionService extractionService = mock(MemoryExtractionService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        MemoryFormationService memoryFormationService = new MemoryFormationService(
                extractionService,
                consolidationService,
                longTermMemoryService,
                Runnable::run
        );
        when(extractionService.extract(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("长期记忆提取两次均未返回有效结构化结果"));

        MemoryFormationService.FormationResult result = memoryFormationService.formSynchronously(
                "user-1",
                "conversation-1",
                "把正在进行的项目关闭",
                "用户此前有一个进行中的项目。"
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.failedStage()).isEqualTo("EXTRACTION");
        verifyNoInteractions(consolidationService, longTermMemoryService);
    }

    @Test
    void shouldPassRecentConversationContextToConsolidation() {
        MemoryExtractionService extractionService = mock(MemoryExtractionService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        MemoryFormationService memoryFormationService = new MemoryFormationService(
                extractionService,
                consolidationService,
                longTermMemoryService,
                Runnable::run
        );
        String recentContext = "助手：当前只有学习 Java 语法并整理资料这一项任务。";
        MemoryCandidate candidate = new MemoryCandidate(
                MemoryType.TASK,
                "task.java.learning_syntax",
                "关闭刚才提到的学习 Java 语法并整理资料任务。",
                "关闭学习 Java 资料整理任务",
                0.8,
                1.0,
                MemoryOperation.DELETE,
                null
        );
        MemoryConsolidationResult consolidationResult =
                new MemoryConsolidationResult(List.of(
                        new MemoryConsolidationResult.Decision(
                                0,
                                MemoryConsolidationResult.Action.IGNORE,
                                List.of()
                        )
                ));

        when(extractionService.extract(
                "关闭这个任务",
                "",
                recentContext
        )).thenReturn(List.of(candidate));
        when(consolidationService.consolidate(
                "user-1",
                List.of(candidate),
                recentContext
        )).thenReturn(consolidationResult);
        when(longTermMemoryService.applyDecision(
                "user-1",
                "conversation-1",
                candidate,
                consolidationResult.decisions().getFirst()
        )).thenReturn(new MemoryWriteResult(
                MemoryWriteResult.Action.IGNORED,
                null
        ));

        memoryFormationService.formSynchronously(
                "user-1",
                "conversation-1",
                "关闭这个任务",
                recentContext
        );

        verify(consolidationService).consolidate(
                "user-1",
                List.of(candidate),
                recentContext
        );
    }
}
