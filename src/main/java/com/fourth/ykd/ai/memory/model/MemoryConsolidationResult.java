package com.fourth.ykd.ai.memory.model;

import java.util.List;
import java.util.Objects;

/**
 * AI 对候选记忆和已有长期记忆进行语义比较后的结果。
 * MemoryCandidate 负责描述“从本轮对话中提取出了什么”，
 * MemoryConsolidationResult 负责描述 这条候选记忆和数据库里的旧记忆是什么关系？
 * 应该创建、确认、替换、删除，还是忽略
 * @param decisions 本轮所有候选记忆对应的语义处理决定
 */
public record  MemoryConsolidationResult(
        //一轮模型比较可能产生多个决定
        List<Decision> decisions
) {

    /**
     * 对模型返回结果进行基础清理。
     * 这里只处理空集合和空元素，不在数据模型中执行数据库校验。
     * 候选下标、记忆编号、用户归属和记忆状态将在业务服务中校验。
     */
    public MemoryConsolidationResult {
        decisions = decisions == null
                ? List.of()
                : decisions.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * AI 对一条候选记忆作出的处理决定。
     * @param candidateIndex 候选记忆在本轮候选列表中的下标，从 0开始
     * @param action AI 判断应该执行的语义操作
     * @param targetMemoryIds 本次操作指向的已有长期记忆编号
     */
    public record Decision(
            int candidateIndex,
            Action action,
            List<String> targetMemoryIds
    ) {

        /**
         * 清理模型可能返回的空编号、空字符串和重复编号。
         * 这里只做结构清理，不相信模型返回的编号一定有效。
         * 后续服务仍然必须校验这些编号是否属于当前用户，
         * 是否为允许操作的记忆，以及是否来自本次提供给模型的记忆列表。
         */
        public Decision {
            targetMemoryIds = targetMemoryIds == null
                    ? List.of()
                    : targetMemoryIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    //删除空字符串
                    .filter(memoryId -> !memoryId.isEmpty())
                    //去重
                    .distinct()
                    .toList();
        }
    }

    /**
     * AI 语义合并阶段可以作出的决定。
     */
    public enum Action {

        /**
         * 当前事实在已有记忆中不存在，应创建新记忆。
         */
        CREATE,

        /**
         * 已有记忆表达了相同事实和相同取值，只需要刷新确认信息。
         */
        CONFIRM,

        /**
         * 当前事实改变了，应停用命中的旧记忆并创建新版本。
         */
        REPLACE,

        /**
         * 用户明确要求忘记某项记忆，或者某项任务已完成。
         */
        DELETE,

        /**
         * 当前候选不值得保存，或者无法安全确定应该如何处理。
         */
        IGNORE
    }
}