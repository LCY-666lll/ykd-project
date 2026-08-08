package com.fourth.ykd.ai.memory.index;

import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆 Redis 向量索引适配层。
 * 主要职责：
 * 1. 将 SQLite 中的 MemoryItem 转换为 Redis 向量文档；
 * 2. 新增或更新 Redis 中的长期记忆索引；
 * 3. 删除 Redis 中指定记忆的索引；
 * 4. 根据用户问题执行语义搜索并返回 memoryId。
 * SQLite 中的 agent_memory 是长期记忆唯一事实来源。
 * Redis 只作为语义搜索索引，搜索完成后仍需根据 memoryId
 * 回到 SQLite 读取完整且可信的 MemoryItem。
 */
@Component
public class  RedisMemoryVectorIndex {

    /**
     * Spring AI 向量存储接口。
     * 当前实际注入的是 RedisVectorStore，
     * 通过 VectorStore 接口降低当前类对具体向量数据库的依赖。
     */
    private final VectorStore vectorStore;

    // Redis 语义查询接受的最低相似度分数。
    private final double similarityThreshold;

    // 统一解释 SQLite LocalDateTime 的业务时区。
    private final ZoneId memoryZoneId;

    public RedisMemoryVectorIndex(
            VectorStore vectorStore,
            @Value("${memory.redis-vector.similarity-threshold:0.65}")
            double similarityThreshold,
            @Value("${memory.redis-vector.timezone:Asia/Shanghai}")
            String memoryTimezone
    ) {
        this.vectorStore = vectorStore;

        if (Double.isNaN(similarityThreshold)
                || similarityThreshold < 0.0
                || similarityThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "Redis 向量相似度阈值必须在 0 到 1 之间"
            );
        }

        if (!StringUtils.hasText(memoryTimezone)) {
            throw new IllegalArgumentException(
                    "Redis 向量记忆时区不能为空"
            );
        }

        try {
            this.memoryZoneId = ZoneId.of(memoryTimezone.trim());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "Redis 向量记忆时区无效",
                    exception
            );
        }

        this.similarityThreshold = similarityThreshold;
    }

    /**
     * 将一条 SQLite 长期记忆 新增 或 更新 到 Redis 向量索引。
     * 执行流程：
     * 1. 校验 MemoryItem 的基础字段；
     * 2. 将 MemoryItem 转换成 Spring AI Document；
     * 3. 包装成文档列表；
     * 4. 调用 VectorStore 写入 Redis。
     * Document ID 直接使用 memoryId，
     * 同一个 memoryId 再次同步时用于更新对应索引文档。
     * @param memoryItem SQLite 中已经存在的完整长期记忆
     */
    public void upsert(MemoryItem memoryItem) {
        // 先校验记忆，再转换成 Redis 向量文档
        Document document = toDocument(
                requireMemoryItem(memoryItem)
        );
        // VectorStore 支持批量写入，所以单条文档也需要包装成 List
        vectorStore.add(List.of(document));
    }

    /**
     * 根据 memoryId 删除 Redis 中的一条向量索引文档。
     * 该操作只影响 Redis 搜索索引，
     * 不会删除 SQLite 中的长期记忆事实。
     * @param memoryId 要从 Redis 索引中删除的长期记忆 ID
     */
    public void deleteByMemoryId(String memoryId) {
        if (!StringUtils.hasText(memoryId)) {
            throw new IllegalArgumentException(
                    "memoryId 不能为空"
            );
        }

        // VectorStore.delete() 接收文档 ID 列表
        vectorStore.delete(
                //delete()接收的是 ID 列表，所以即使只删除一个，也要包装成List.of()
                List.of(memoryId.trim())
        );
    }

    /**
     * 在当前用户的 ACTIVE 长期记忆中执行语义相似度搜索。
     * 对于当前用户的这个问题，Redis 里哪些 ACTIVE 长期记忆语义最相关
     * 执行流程：
     * 1. 校验 userId、query 和 limit；
     * 2. 将原始 userId 转换为 Redis 查询使用的 userScope；
     * 3. 创建向量搜索请求；
     * 4. 限制只查询当前用户并且状态为 ACTIVE 的记忆；
     * 5. 执行相似度搜索；
     * 6. 提取并去重 Document ID；
     * 7. 返回 memoryId 列表。
     * 这里只返回 memoryId，不直接把 Redis Document 当作最终事实。
     * 调用方应根据返回的 ID 回到 SQLite 查询完整 MemoryItem。
     * @param userId 当前微信用户 ID
     * @param query  用户本轮提出的问题
     * @param limit  最多返回的记忆数量
     * @return 当前用户语义检索命中的长期记忆 ID 列表
     */
    public List<String> searchActiveMemoryIds(
            String userId,
            String query,
            int limit
    ) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            // 没有用户或搜索文本时，本轮不执行向量检索
            return List.of();
        }

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit 必须大于 0"
            );
        }

        //使用固定十六进制 userScope 做 Redis TAG 用户隔离
        String userScope = RedisMemoryUserScope.fromUserId(userId);

        //配置搜索请求
        SearchRequest searchRequest = SearchRequest.builder()
                        .query(query.trim())
                         //指定最多返回多少条最相似结果
                        .topK(limit)
                        // 使用配置的最低相似度阈值，过滤弱相关记忆
                        .similarityThreshold(similarityThreshold)
                        // 只能查询当前用户并且状态为 ACTIVE 的记忆
                        .filterExpression(
                                "userScope == '"
                                        + userScope
                                        + "'"
                                        + " && status == '"
                                        + MemoryStatus.ACTIVE.name()
                                        + "'"
                        )
                        .build();


        /* 执行相似度搜索
        query 文本
           ↓
        EmbeddingModel 转换查询向量
           ↓
        Redis 搜索相似向量
           ↓
        先限制 userScope 和 ACTIVE
           ↓
        返回符合条件的 Document里的 ID 列表*/
        return vectorStore
                .similaritySearch(searchRequest)
                .stream()
                // Redis Document 的 ID 就是 SQLite memoryId
                .map(Document::getId)
                // 防止异常情况下返回重复记忆 ID
                .distinct()
                .toList();
    }

    /**
     * 将 SQLite MemoryItem 转换成 Spring AI Redis 向量文档。
     * Redis VectorStore 操作的不是：MemoryItem,而是：Document
     * Document 组成：
     * 1. ID：直接使用 memoryId；
     * 2. 文本：优先使用 summary，content 作为兜底；
     * 3. 元数据：保存用户、状态、类型、重要度等过滤和重排字段。
     * 元数据就是附加在向量文档上的结构化字段
     * @param memoryItem 已通过基础字段校验的 SQLite 长期记忆
     * @return 可以写入 Redis VectorStore 的 Document
     */
    private Document toDocument(MemoryItem memoryItem) {
        // LinkedHashMap 保留字段插入顺序，方便调试和查看元数据
        Map<String, Object> metadata = new LinkedHashMap<>();

        // 保存原始用户 ID，主要用于排查和数据核对
        metadata.put(
                "userId",
                memoryItem.userId().trim()
        );

        // 实际 Redis 过滤使用哈希后的 userScope，避免特殊字符问题
        metadata.put(
                "userScope",
                RedisMemoryUserScope.fromUserId(
                        memoryItem.userId()
                )
        );

        metadata.put(
                "memoryId",
                memoryItem.id().trim()
        );
        metadata.put(
                "memoryType",
                memoryItem.type().name()
        );
        metadata.put(
                "status",
                memoryItem.status().name()
        );
        metadata.put(
                "importance",
                memoryItem.importance()
        );
        metadata.put(
                "confidence",
                memoryItem.confidence()
        );
        metadata.put(
                "updatedAt",
                toEpochMilli(memoryItem.updatedAt())
        );

        // EPISODE、ARTIFACT 等记忆可能没有稳定业务键
        if (StringUtils.hasText(memoryItem.memoryKey())) {
            metadata.put(
                    "memoryKey",
                    memoryItem.memoryKey().trim()
            );
        }

        /*new Document(
                文档ID,
                用于向量化的文本,
                元数据
        );*/
        return new Document(
                memoryItem.id().trim(),
                resolveDocumentContent(memoryItem),
                metadata
        );
    }

    /**
     * 选择用于向量化和语义检索的文本,决定到底拿长期记忆里的哪段文字生成向量
     * 选择顺序：
     * 1. summary 有内容时优先使用精炼摘要；
     * 2. summary 为空时降级使用完整 content；
     * 3. 两者都为空时拒绝创建向量文档。
     * @param memoryItem SQLite 长期记忆
     * @return 用于生成向量的非空文本
     */
    private String resolveDocumentContent(
            MemoryItem memoryItem
    ) {
        if (StringUtils.hasText(memoryItem.summary())) {
            // 精炼摘要通常比完整内容更适合语义检索
            return memoryItem.summary().trim();
        }

        if (StringUtils.hasText(memoryItem.content())) {
            // 摘要不可用时，降级使用完整记忆内容
            return memoryItem.content().trim();
        }

        throw new IllegalArgumentException(
                "长期记忆摘要和内容不能同时为空"
        );
    }

    /**
     * 将 LocalDateTime 转换成 Redis NUMERIC 字段使用的毫秒时间戳。
     * 转换流程：
     * 1. 使用配置的业务时区解释 LocalDateTime；
     * 2. 转换成时间轴上的 Instant；
     * 3. 转换成从 1970-01-01 UTC 开始计算的毫秒数。
     * @param updatedAt SQLite 中保存的最后更新时间
     * @return Redis NUMERIC 元数据使用的毫秒时间戳
     */
    private long toEpochMilli(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            throw new IllegalArgumentException(
                    "长期记忆更新时间不能为空"
            );
        }
        return updatedAt
                // LocalDateTime 本身没有时区，需要补充统一业务时区
                .atZone(memoryZoneId)
                .toInstant()
                .toEpochMilli();
    }

    /**
     * 校验 MemoryItem 是否具备建立 Redis 索引所需的基础字段。
     * 必填字段：
     * 1. MemoryItem 对象；
     * 2. 记忆 ID；
     * 3. 用户 ID；
     * 4. 记忆类型；
     * 5. 记忆状态。
     * @param memoryItem 待同步到 Redis 的 SQLite 长期记忆
     * @return 校验通过后的原始 MemoryItem
     */
    private MemoryItem requireMemoryItem(
            MemoryItem memoryItem
    ) {
        if (memoryItem == null) {
            throw new IllegalArgumentException(
                    "长期记忆不能为空"
            );
        }

        if (!StringUtils.hasText(memoryItem.id())) {
            throw new IllegalArgumentException(
                    "长期记忆 ID 不能为空"
            );
        }

        if (!StringUtils.hasText(memoryItem.userId())) {
            throw new IllegalArgumentException(
                    "长期记忆用户 ID 不能为空"
            );
        }

        if (memoryItem.type() == null
                || memoryItem.status() == null) {
            throw new IllegalArgumentException(
                    "长期记忆类型和状态不能为空"
            );
        }

        // 当前方法只负责校验，成功后原样返回
        return memoryItem;
    }
}