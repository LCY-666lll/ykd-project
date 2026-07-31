package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 负责为当前用户问题检索需要注入主模型的长期记忆。
 * 当前阶段只使用 SQLite 进行结构化检索，
 * 保持原 LongTermMemoryAdvisor 的查询行为不变：
 * 只读取当前用户状态为 ACTIVE、尚未过期且排序靠前的八条记忆。
 * 后续接入 Redis Vector Store 时，
 * SQLite 精确记忆、Redis 语义记忆以及最终排序都会集中在该服务中完成，
 * LongTermMemoryAdvisor 只负责安全包装和注入，不承担检索策略。
 */
@Service
@RequiredArgsConstructor
public class MemoryRetrievalService {

    /**
     * 单次最多返回八条长期记忆。
     * 该限制控制进入主模型的候选数量，不参与长期记忆语义判断。
     */
    private static final int MAX_MEMORY_ITEMS = 8;

    //SQLite 是结构化长期记忆的事实源，当前阶段所有召回都从这里读取。
    private final SqliteLongTermMemoryRepository memoryRepository;

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
        return memoryRepository.findActiveByUserId(
                userId.trim(),
                MAX_MEMORY_ITEMS
        );
    }
}
