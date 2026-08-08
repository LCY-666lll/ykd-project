package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.model.MemoryCandidate;
import com.fourth.ykd.ai.memory.model.MemoryConsolidationResult;
import com.fourth.ykd.ai.memory.model.MemoryWriteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 负责调度一轮完整的长期记忆形成流程。
 * 普通聊天通过专用线程池异步执行，明确的记忆管理命令同步执行并返回真实写库结果。
 *
 * 该服务本身不判断记忆语义：
 * MemoryExtractionService 负责从对话中提取候选；
 * MemoryConsolidationService 负责比较候选和已有 ACTIVE 记忆；
 * LongTermMemoryService 负责按照可信 Decision 完成事务写库。
 * 用户正常聊天完成
 *         ↓
 * MemoryFormationService.submit()
 *         ↓
 * 任务提交给 memoryExecutor 线程池
 *         ↓
 * 后台线程执行 formMemory()
 *         ↓
 * MemoryExtractionService.extract()
 *         ↓
 * 得到 List<MemoryCandidate>
 *         ↓
 * MemoryConsolidationService.consolidate()
 *         ↓
 * 得到每条候选对应的 Decision
 *         ↓
 * 逐条调用 LongTermMemoryService.applyDecision()
 *         ↓
 * 校验、去重、版本替换、软删除、写入 SQLite
 */
@Slf4j
@Service
public class MemoryFormationService {

    //把用户消息和助手回答交给 AI，提取候选记忆
    private final MemoryExtractionService extractionService;
    //把候选记忆和当前用户已有记忆交给 AI，得到可信的语义合并决定
    private final MemoryConsolidationService consolidationService;
    //把某一条候选记忆可靠写进数据库
    private final LongTermMemoryService longTermMemoryService;
    //专门执行记忆任务的线程池
    private final Executor memoryExecutor;

    /**
     * 注入记忆提取、语义合并、事务写库服务以及专用后台线程池。
     */
    public MemoryFormationService(
            MemoryExtractionService extractionService,
            MemoryConsolidationService consolidationService,
            LongTermMemoryService longTermMemoryService,
            @Qualifier("memoryExecutor") Executor memoryExecutor
    ) {
        this.extractionService = extractionService;
        this.consolidationService = consolidationService;
        this.longTermMemoryService = longTermMemoryService;
        this.memoryExecutor = memoryExecutor;
    }

    /**
     * 提交普通对话的后台记忆形成任务。
     * 该方法只负责把任务放进线程池，会立即返回。
     */
    public void submit(String userId, String conversationId, String userMessage, String assistantReply) {
        if (!StringUtils.hasText(userId)
                || !StringUtils.hasText(userMessage)) {
            return;
        }
        try {
            CompletableFuture.runAsync(
                    () -> formMemory(
                            userId,
                            conversationId,
                            userMessage,
                            assistantReply,
                            ""
                    ),
                    memoryExecutor
            );
        } catch (RejectedExecutionException exception) {
            log.warn(
                    "[AI][MEMORY_FORMATION][REJECTED] userId={}",
                    userId
            );
        }
    }

    /**
     * 同步执行明确的记忆管理请求。
     * 调用方必须等待提取、合并和 SQLite 写入全部结束，
     * 再根据 FormationResult 生成“是否真的记住或删除”的中文回复。
     *
     * @param userId 当前微信用户 ID
     * @param conversationId 产生本次记忆的会话 ID
     * @param userMessage 用户本轮明确的记忆管理命令
     * @param recentConversationContext 用于解析本轮省略和指代的近期会话
     * @return 本轮各类真实写库结果数量；参数无效时返回 VALIDATION 失败
     */
    public FormationResult formSynchronously(
            String userId,
            String conversationId,
            String userMessage,
            String recentConversationContext
    ) {
        if (!StringUtils.hasText(userId)
                || !StringUtils.hasText(userMessage)) {
            return FormationResult.failed("VALIDATION");
        }

        return formMemory(
                userId,
                conversationId,
                userMessage,
                "",
                recentConversationContext
        );
    }

    /**
     * 执行一次完整的记忆形成流程。
     * 提取候选记忆
     * ↓
     * 没有候选 → 直接完成
     * ↓
     * AI 比较候选记忆和数据库旧记忆
     * ↓
     * 得到 decisions
     * ↓
     * 逐条执行 decision
     * ↓
     * 统计 CREATED、CONFIRMED、REPLACED 等数量
     * ↓
     * 返回 FormationResult
     *
     * 单条 Decision 写库失败只累计 failedCount，不阻止剩余候选继续处理；
     * 提取阶段或合并阶段整体失败时，使用 failedStage 表明失败位置。
     */
    private FormationResult formMemory(
            String userId,
            String conversationId,
            String userMessage,
            String assistantReply,
            String recentConversationContext
    ) {
        long startedAt = System.nanoTime();
        long extractionElapsedMs = 0L;
        long consolidationElapsedMs = 0L;
        final List<MemoryCandidate> candidates;

        try {
            /*提取候选记忆，返回 List<MemoryCandidate>。例如：
            候选 1：用户默认天气城市为杭州
            候选 2：用户正在整理家庭旅行相册
            候选 3：用户希望忘掉此前保存的称呼*/
            long extractionStartedAt = System.nanoTime();
            candidates = extractionService.extract(
                    userMessage,
                    assistantReply,
                    recentConversationContext
            );
            extractionElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - extractionStartedAt);
        } catch (RuntimeException exception) {
            log.error(
                    "[AI][MEMORY_FORMATION][EXTRACTION_FAILED] userId={}",
                    userId,
                    exception
            );
            //提取失败要结束整轮任务
            return FormationResult.failed("EXTRACTION");
        }

        //为每一种实际写库结果建立计数器，供同步回复和汇总日志使用。
        Map<MemoryWriteResult.Action, Integer> actionCounts =
                new EnumMap<>(MemoryWriteResult.Action.class);
        for (MemoryWriteResult.Action action
                : MemoryWriteResult.Action.values()) {
            actionCounts.put(action, 0);
        }

        if (candidates.isEmpty()) {
            logCompleted(
                    userId,
                    candidates.size(),
                    actionCounts,
                    0,
                    extractionElapsedMs,
                    consolidationElapsedMs,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            );
            return buildResult(
                    candidates.size(),
                    actionCounts,
                    0,
                    null
            );
        }

        final MemoryConsolidationResult consolidationResult;

        try {
            long consolidationStartedAt = System.nanoTime();
            consolidationResult = consolidationService.consolidate(
                    userId,
                    candidates,
                    recentConversationContext
            );
            consolidationElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - consolidationStartedAt);
        } catch (RuntimeException exception) {
            log.error(
                    "[AI][MEMORY_FORMATION][CONSOLIDATION_FAILED] userId={}",
                    userId,
                    exception
            );
            return FormationResult.failed("CONSOLIDATION");
        }

        //初始化失败数量：记录：有多少条候选记忆写入失败
        int failedCount = 0;

        //每次从 candidates 中取出一条候选记忆，放进变量 candidate
        for (MemoryConsolidationResult.Decision decision
                : consolidationResult.decisions()) {
            MemoryCandidate candidate =
                    candidates.get(decision.candidateIndex());
            //每一条候选有独立的 try-catch:单条候选写入失败只影响当前候选，不会阻止其他候选继续处理
            try {
                MemoryWriteResult writeResult =
                        longTermMemoryService.applyDecision(
                                userId,
                                conversationId,
                                candidate,
                                decision
                        );
                actionCounts.compute(
                        writeResult.action(),
                        (action, count) -> count == null
                                ? 1
                                : count + 1
                );
            } catch (RuntimeException exception) {
                failedCount++;
                log.error(
                        "[AI][MEMORY_FORMATION][DECISION_FAILED] "
                                + "userId={}, candidateType={}, action={}",
                        userId,
                        candidate == null ? null : candidate.type(),
                        decision.action(),
                        exception
                );
            }
        }
        logCompleted(
                userId,
                candidates.size(),
                actionCounts,
                failedCount,
                extractionElapsedMs,
                consolidationElapsedMs,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        );
        return buildResult(
                candidates.size(),
                actionCounts,
                failedCount,
                null
        );
    }

    /**
     * 把内部 Action 计数统一转换成对外返回的 FormationResult。
     */
    private FormationResult buildResult(
            int candidateCount,
            Map<MemoryWriteResult.Action, Integer> actionCounts,
            int failedCount,
            String failedStage
    ) {
        return new FormationResult(
                candidateCount,
                actionCounts.get(MemoryWriteResult.Action.CREATED),
                actionCounts.get(MemoryWriteResult.Action.CONFIRMED),
                actionCounts.get(MemoryWriteResult.Action.REPLACED),
                actionCounts.get(MemoryWriteResult.Action.DELETED),
                actionCounts.get(MemoryWriteResult.Action.IGNORED),
                failedCount,
                failedStage
        );
    }

    /**
     * 记录本轮记忆形成的汇总结果，不输出用户消息和记忆正文。
     */
    private void logCompleted(
            String userId,
            int candidateCount,
            Map<MemoryWriteResult.Action, Integer> actionCounts,
            int failedCount,
            long extractionElapsedMs,
            long consolidationElapsedMs,
            long totalElapsedMs
    ) {
        log.info(
                "[AI][MEMORY_FORMATION][COMPLETED] "
                        + "userId={}, candidateCount={}, "
                        + "created={}, confirmed={}, replaced={}, "
                        + "deleted={}, ignored={}, failedCount={}, extractionMs={}, consolidationMs={}, totalMs={}",
                userId,
                candidateCount,
                actionCounts.get(MemoryWriteResult.Action.CREATED),
                actionCounts.get(MemoryWriteResult.Action.CONFIRMED),
                actionCounts.get(MemoryWriteResult.Action.REPLACED),
                actionCounts.get(MemoryWriteResult.Action.DELETED),
                actionCounts.get(MemoryWriteResult.Action.IGNORED),
                failedCount,
                extractionElapsedMs,
                consolidationElapsedMs,
                totalElapsedMs
        );
    }

    /**
     * 一轮同步或异步记忆形成的执行结果。
     * 各计数字段表示实际 Decision 执行次数，不表示受影响的数据库总行数。
     */
    public record FormationResult(
            int candidateCount,
            int createdCount,
            int confirmedCount,
            int replacedCount,
            int deletedCount,
            int ignoredCount,
            int failedCount,
            String failedStage
    ) {
        /**
         * 创建一个在指定整体阶段失败、且没有成功计数的结果。
         */
        public static FormationResult failed(String failedStage) {
            return new FormationResult(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    failedStage
            );
        }

        /**
         * 判断提取和合并主流程是否完整结束。
         * 单条写库失败通过 failedCount 表示，不会改变该字段。
         */
        public boolean completed() {
            return failedStage == null;
        }
    }
}
