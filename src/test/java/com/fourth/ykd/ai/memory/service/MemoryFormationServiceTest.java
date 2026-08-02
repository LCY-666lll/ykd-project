package com.fourth.ykd.ai.memory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
}