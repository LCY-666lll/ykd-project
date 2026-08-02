package com.fourth.ykd.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fourth.ykd.ai.memory.service.MemoryFormationService;
import org.junit.jupiter.api.Test;

class AiChatServiceImplMemoryManagementReplyTest {

    @Test
    void shouldReplySavedWhenMemoryWasCreated() {
        MemoryFormationService.FormationResult result =
                new MemoryFormationService.FormationResult(
                        1,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        null
                );

        assertThat(AiChatServiceImpl.buildMemoryManagementReply(result))
                .isEqualTo("已保存本次长期记忆。");
    }

    @Test
    void shouldReplyUpdatedWhenMemoryWasReplaced() {
        MemoryFormationService.FormationResult result =
                new MemoryFormationService.FormationResult(
                        1,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0,
                        null
                );

        assertThat(AiChatServiceImpl.buildMemoryManagementReply(result))
                .isEqualTo("已更新本次长期记忆。");
    }

    @Test
    void shouldNotClaimSuccessWhenNothingChanged() {
        MemoryFormationService.FormationResult result =
                new MemoryFormationService.FormationResult(
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        null
                );

        assertThat(AiChatServiceImpl.buildMemoryManagementReply(result))
                .isEqualTo("本次没有识别到需要保存或变更的长期记忆，请说得更明确一些。");
    }

    @Test
    void shouldReplyFailureWhenFormationFailed() {
        MemoryFormationService.FormationResult result =
                MemoryFormationService.FormationResult.failed("EXTRACTION");

        assertThat(AiChatServiceImpl.buildMemoryManagementReply(result))
                .isEqualTo("本次长期记忆处理未完成，请稍后重试或换一种更明确的说法。");
    }
}