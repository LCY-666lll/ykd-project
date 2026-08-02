package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.index.RedisMemoryVectorIndex;
import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 负责为当前用户问题检索需要注入主模型的长期记忆。
 * 当前阶段只使用 SQLite 进行结构化检索，
 * 保持原 LongTermMemoryAdvisor 的查询行为不变：
 * 只读取当前用户状态为 ACTIVE、尚未过期且排序靠前的八条记忆。
 * 后续接入 Redis Vector Store 时，
 * SQLite 精确记忆、Redis 语义记忆以及最终排序都会集中在该服务中完成，
 * LongTermMemoryAdvisor 只负责安全包装和注入，不承担检索策略。
 */
@Slf4j
@Service
public class MemoryRetrievalService {

    /**
     * 单次最多返回八条长期记忆。
     * 该限制控制进入主模型的候选数量，不参与长期记忆语义判断。
     */
    private static final int MAX_MEMORY_ITEMS = 8;

    //SQLite 是结构化长期记忆的事实源，当前阶段所有召回都从这里读取。
    private final SqliteLongTermMemoryRepository memoryRepository;
    //Redis 只提供语义候选 ID，不作为长期记忆事实来源。
    private final ObjectProvider<RedisMemoryVectorIndex> memoryVectorIndexProvider;

    /**
     * Spring 正式运行时使用的构造方法。
     * @param memoryRepository SQLite 长期记忆仓储
     * @param memoryVectorIndexProvider 可选 Redis 向量索引提供者
     */
    @Autowired
    public MemoryRetrievalService(
            SqliteLongTermMemoryRepository memoryRepository,
            ObjectProvider<RedisMemoryVectorIndex> memoryVectorIndexProvider
    ) {
        this.memoryRepository = memoryRepository;
        this.memoryVectorIndexProvider = memoryVectorIndexProvider;
    }

    /**
     * 保留原有单参数构造方式，
     * 供不需要 Redis 的已有单元测试继续使用。
     * @param memoryRepository SQLite 长期记忆仓储
     */
    public MemoryRetrievalService(
            SqliteLongTermMemoryRepository memoryRepository
    ) {
        this(
                memoryRepository,
                new ObjectProvider<>() {
                    @Override
                    public RedisMemoryVectorIndex getObject() {
                        return null;
                    }
                }
        );
    }

    /**
     * 查询当前用户需要注入主模型的长期记忆。
     * 当前阶段仍按重要性、可信度和更新时间读取 SQLite 前八条，
     * userQuery 暂不改变查询结果；保留该参数是为了让后续 Redis
     * 语义检索接入时不再修改 Advisor 与检索服务之间的调用协议。
     * @param userId 当前微信用户 ID
     * @param userQuery 用户本轮问题，后续用于语义检索
     * @return 当前阶段符合条件的 SQLite 长期记忆
     */
    public List<MemoryItem> retrieve(
            String userId,
            String userQuery
    ) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }

        //当前阶段只执行 SQLite 结构化召回，阶段二后续再使用 userQuery 查询 Redis。
        //Redis 语义候选会在 SQLite 结构化召回完成后合并，不改变 SQLite 的事实源职责。
        String currentUserId = userId.trim();

        //无论 Redis 是否可用，始终先查询 SQLite，保证原有长期记忆链路稳定。
        List<MemoryItem> sqliteMemories =
                memoryRepository.findActiveByUserId(
                        currentUserId,
                        MAX_MEMORY_ITEMS
                );

        if (!StringUtils.hasText(userQuery)) {
            return sqliteMemories;
        }

        RedisMemoryVectorIndex memoryVectorIndex =
                memoryVectorIndexProvider.getIfAvailable();

        if (memoryVectorIndex == null) {
            return sqliteMemories;
        }

        long redisRetrievalStartedAt = System.nanoTime();
        try {
            List<String> semanticMemoryIds =
                    memoryVectorIndex.searchActiveMemoryIds(
                            currentUserId,
                            userQuery.trim(),
                            MAX_MEMORY_ITEMS
                    );

            List<MemoryItem> mergedMemories = mergeMemories(
                    currentUserId,
                    semanticMemoryIds,
                    sqliteMemories
            );
            log.info(
                    "[AI][LONG_TERM_MEMORY][REDIS_RETRIEVAL] status=SUCCESS, userId={}, candidateCount={}, mergedCount={}, elapsedMs={}",
                    currentUserId,
                    semanticMemoryIds.size(),
                    mergedMemories.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - redisRetrievalStartedAt)
            );
            return mergedMemories;
        } catch (RuntimeException exception) {
            //Redis 查询失败时必须保持 SQLite 原有召回，不影响普通聊天。
            log.warn(
                    "[AI][LONG_TERM_MEMORY][REDIS_RETRIEVAL] status=FALLBACK, userId={}, reason={}, elapsedMs={}",
                    currentUserId,
                    exception.getMessage(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - redisRetrievalStartedAt)
            );
            return sqliteMemories;
        }
    }

    /**
     * 合并 Redis 语义候选和 SQLite 结构化候选。
     * Redis 返回顺序已经按相似度排列，
     * SQLite 候选只用于保底补足，不覆盖 Redis 的语义顺序。
     * @param userId 当前微信用户 ID
     * @param semanticMemoryIds Redis 返回的记忆 ID
     * @param sqliteMemories SQLite 原有结构化候选
     * @return 去重并限制数量后的记忆列表
     */
    private List<MemoryItem> mergeMemories(
            String userId,
            List<String> semanticMemoryIds,
            List<MemoryItem> sqliteMemories
    ) {
        Map<String, MemoryItem> mergedMemories = new LinkedHashMap<>();

        for (String memoryId : semanticMemoryIds) {
            if (!StringUtils.hasText(memoryId)) {
                continue;
            }

            memoryRepository.findActiveByIdAndUserId(userId, memoryId)
                    .ifPresent(memory -> mergedMemories.putIfAbsent(memory.id(), memory));
        }

        for (MemoryItem sqliteMemory : sqliteMemories) {
            mergedMemories.putIfAbsent(sqliteMemory.id(), sqliteMemory);
        }

        return mergedMemories.values().stream().limit(MAX_MEMORY_ITEMS).toList();
    }
}
