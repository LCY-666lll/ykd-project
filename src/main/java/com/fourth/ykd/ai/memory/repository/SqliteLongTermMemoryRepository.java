package com.fourth.ykd.ai.memory.repository;

import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import com.fourth.ykd.ai.memory.model.MemoryType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 结构化长期记忆仓库。
 * 该类只负责 agent_memory 表的数据库读写，
 */
@Repository
@RequiredArgsConstructor
public class SqliteLongTermMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 插入一条新的长期记忆。
     */
    private static final String INSERT_SQL = """
            INSERT INTO agent_memory (
                id,
                user_id,
                memory_type,
                memory_key,
                content,
                summary,
                importance,
                confidence,
                status,
                source_conversation_id,
                content_hash,
                supersedes_id,
                expires_at,
                created_at,
                updated_at,
                last_accessed_at,
                access_count
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 根据记忆 ID 查询记录。
     * 该查询不限制状态，用于查看历史版本或执行状态更新。
     */
    private static final String FIND_BY_ID_SQL = """
            SELECT
                id,
                user_id,
                memory_type,
                memory_key,
                content,
                summary,
                importance,
                confidence,
                status,
                source_conversation_id,
                content_hash,
                supersedes_id,
                expires_at,
                created_at,
                updated_at,
                last_accessed_at,
                access_count
            FROM agent_memory
            WHERE id = ?
            LIMIT 1
            """;

    /**
     * 根据用户、记忆类型和稳定业务键查询当前有效版本。
     * 已经过期的记录不会被返回。
     */
    private static final String FIND_ACTIVE_BY_MEMORY_KEY_SQL = """
            SELECT
                id,
                user_id,
                memory_type,
                memory_key,
                content,
                summary,
                importance,
                confidence,
                status,
                source_conversation_id,
                content_hash,
                supersedes_id,
                expires_at,
                created_at,
                updated_at,
                last_accessed_at,
                access_count
            FROM agent_memory
            WHERE user_id = ?
              AND memory_type = ?
              AND memory_key = ?
              AND status = 'ACTIVE'
              AND (
                  expires_at IS NULL
                  OR expires_at > CURRENT_TIMESTAMP
              )
            ORDER BY updated_at DESC
            LIMIT 1
            """;

    /**
     * 用于内容去重:根据用户、记忆类型和内容哈希查询重复记忆。
     * 已经过期的记录不会被当成当前有效重复项。
     */
    private static final String FIND_ACTIVE_BY_CONTENT_HASH_SQL = """
            SELECT
                id,
                user_id,
                memory_type,
                memory_key,
                content,
                summary,
                importance,
                confidence,
                status,
                source_conversation_id,
                content_hash,
                supersedes_id,
                expires_at,
                created_at,
                updated_at,
                last_accessed_at,
                access_count
            FROM agent_memory
            WHERE user_id = ?
              AND memory_type = ?
              AND content_hash = ?
              AND status = 'ACTIVE'
              AND (
                  expires_at IS NULL
                  OR expires_at > CURRENT_TIMESTAMP
              )
            ORDER BY updated_at DESC
            LIMIT 1
            """;

    /**
     * 查询某个用户当前有效的长期记忆。
     * 结果优先按照重要性、可信度和更新时间排序。
     */
    private static final String FIND_ACTIVE_BY_USER_ID_SQL = """
            SELECT
                id,
                user_id,
                memory_type,
                memory_key,
                content,
                summary,
                importance,
                confidence,
                status,
                source_conversation_id,
                content_hash,
                supersedes_id,
                expires_at,
                created_at,
                updated_at,
                last_accessed_at,
                access_count
            FROM agent_memory
            WHERE user_id = ?
              AND status = 'ACTIVE'
              AND (
                  expires_at IS NULL
                  OR expires_at > CURRENT_TIMESTAMP
              )
            ORDER BY
                importance DESC,
                confidence DESC,
                updated_at DESC
            LIMIT ?
            """;

    /**
     * 将旧记忆标记为已经被新版本替代。
     */
    private static final String MARK_SUPERSEDED_SQL = """
            UPDATE agent_memory
            SET status = 'SUPERSEDED',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND status = 'ACTIVE'
            """;

    /**
     * 将记忆标记为已删除。
     * 使用软删除保留历史记录，不直接执行 DELETE。
     */
    private static final String MARK_DELETED_SQL = """
            UPDATE agent_memory
            SET status = 'DELETED',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND status <> 'DELETED'
            """;

    /**
     * 更新记忆的访问统计。
     */
    private static final String INCREMENT_ACCESS_SQL = """
            UPDATE agent_memory
            SET last_accessed_at = CURRENT_TIMESTAMP,
                access_count = access_count + 1
            WHERE id = ?
              AND status = 'ACTIVE'
            """;


    /**
     * 当相同记忆再次出现时，更新其确认信息。
     * 已有的重要性和可信度不会被降低。
     * importance = MAX(importance, ?) : 比较数据库中的旧重要性和本次新重要性，保留较大的值
     */
    private static final String REFRESH_CONFIRMATION_SQL = """
        UPDATE agent_memory
        SET importance = MAX(importance, ?),
            confidence = MAX(confidence, ?),
            updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
          AND status = 'ACTIVE'
        """;


    /**
     * JdbcTemplate 查询数据库后得到 ResultSet。
     * RowMapper 的职责是：
     * 读取 ResultSet 当前行  →  转换成 MemoryItem
     */
    private static final RowMapper<MemoryItem> MEMORY_ITEM_ROW_MAPPER =
            (resultSet, rowNum) -> mapMemoryItem(resultSet);

    /**
     * 插入一条长期记忆。
     * ID、内容哈希、状态和时间等字段应当由业务层提前生成。
     * @param memoryItem 需要保存的长期记忆
     * @return 实际写入的行数，正常情况下为 1
     */
    public int insert(MemoryItem memoryItem) {
        validateMemoryItem(memoryItem);

        return jdbcTemplate.update(

                INSERT_SQL,

                /*按照顺序把后面的 Java 参数填入 SQL 的 ?*/
                memoryItem.id(),
                memoryItem.userId(),
                //把枚举：MemoryType.PROJECT转换成数据库字符串：PROJECT
                memoryItem.type().name(),
                normalizeOptionalText(memoryItem.memoryKey()),
                memoryItem.content().trim(),
                memoryItem.summary().trim(),
                memoryItem.importance(),
                memoryItem.confidence(),
                memoryItem.status().name(),
                normalizeOptionalText(memoryItem.sourceConversationId()),
                memoryItem.contentHash(),
                normalizeOptionalText(memoryItem.supersedesId()),
                //Java 的 LocalDateTime 转成 JDBC 能写入的 Timestamp
                toTimestamp(memoryItem.expiresAt()),
                toTimestamp(memoryItem.createdAt()),
                toTimestamp(memoryItem.updatedAt()),
                toTimestamp(memoryItem.lastAccessedAt()),
                memoryItem.accessCount()
        );
    }

    /**
     * 根据 ID 查询一条长期记忆。
     */
    public Optional<MemoryItem> findById(String memoryId) {
        List<MemoryItem> results = jdbcTemplate.query(
                FIND_BY_ID_SQL,
                MEMORY_ITEM_ROW_MAPPER,
                requireText(memoryId, "memoryId")
        );

        return results.stream().findFirst();
    }

    /**
     * 根据稳定业务键查询当前有效版本。
     * 适用于 PROFILE、PREFERENCE、PROJECT 和 TASK 等可替换事实。
     */
    public Optional<MemoryItem> findActiveByMemoryKey(
            String userId,
            MemoryType memoryType,
            String memoryKey
    ) {
        List<MemoryItem> results = jdbcTemplate.query(
                FIND_ACTIVE_BY_MEMORY_KEY_SQL,
                MEMORY_ITEM_ROW_MAPPER,
                requireText(userId, "userId"),
                requireMemoryType(memoryType).name(),
                requireText(memoryKey, "memoryKey")
        );

        return results.stream().findFirst();
    }

    /**
     * 根据内容哈希查询当前有效的重复记忆。
     * 适用于没有 memoryKey 的 EPISODE 和 ARTIFACT，
     * 也可以作为所有类型记忆的第二层去重判断。
     */
    public Optional<MemoryItem> findActiveByContentHash(
            String userId,
            MemoryType memoryType,
            String contentHash
    ) {
        List<MemoryItem> results = jdbcTemplate.query(
                FIND_ACTIVE_BY_CONTENT_HASH_SQL,
                MEMORY_ITEM_ROW_MAPPER,
                requireText(userId, "userId"),
                requireMemoryType(memoryType).name(),
                requireText(contentHash, "contentHash")
        );

        return results.stream().findFirst();
    }

    /**
     * 查询某个用户当前有效且尚未过期的长期记忆。
     * 结果按重要性、可信度和更新时间排序；
     * 当前由 MemoryConsolidationService 缩小已有记忆范围，
     * 也由 MemoryRetrievalService 读取并交给 LongTermMemoryAdvisor 注入主模型。
     */
    public List<MemoryItem> findActiveByUserId(String userId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }

        return jdbcTemplate.query(
                FIND_ACTIVE_BY_USER_ID_SQL,
                MEMORY_ITEM_ROW_MAPPER,
                requireText(userId, "userId"),
                limit
        );
    }

    /**
     * 将一条当前有效记忆标记为已经被新版本替代。
     * @return 实际更新的行数
     */
    public int markSuperseded(String memoryId) {
        return jdbcTemplate.update(
                MARK_SUPERSEDED_SQL,
                requireText(memoryId, "memoryId")
        );
    }

    /**
     * 将一条记忆标记为已删除。
     * @return 实际更新的行数
     */
    public int markDeleted(String memoryId) {
        return jdbcTemplate.update(
                MARK_DELETED_SQL,
                requireText(memoryId, "memoryId")
        );
    }

    /**
     * 增加一条记忆的访问次数，并更新最后访问时间。
     * @return 实际更新的行数
     */
    public int incrementAccess(String memoryId) {
        return jdbcTemplate.update(
                INCREMENT_ACCESS_SQL,
                requireText(memoryId, "memoryId")
        );
    }


    /**
     * 刷新一条重复记忆的确认信息。
     * 已有的重要性和可信度只会保持或提高，不会降低。
     * @param memoryId 记忆 ID
     * @param importance 本次提取的重要程度
     * @param confidence 本次提取的可信程度
     * @return 实际更新的行数
     */
    public int refreshConfirmation(
            String memoryId,
            double importance,
            double confidence
    ) {
        validateScore(importance, "记忆重要程度");
        validateScore(confidence, "记忆可信程度");

        return jdbcTemplate.update(
                REFRESH_CONFIRMATION_SQL,
                importance,
                confidence,
                requireText(memoryId, "memoryId")
        );
    }

    /**
     * 校验评分是否处于 0 到 1 之间。
     */
    private static void validateScore(double score, String fieldName) {
      if (score < 0.0 || score>1.0){
          throw new IllegalArgumentException(fieldName+"必须在0到1之间");
      }
    }


    /**
     * 将数据库中的一行转换为 MemoryItem。
     */
    private static MemoryItem mapMemoryItem(ResultSet resultSet) throws SQLException {
        return new MemoryItem(

                //字段顺序与 MemoryItem record 的参数顺序必须一致，否则会发生数据错位
                resultSet.getString("id"),
                resultSet.getString("user_id"),
                MemoryType.valueOf(resultSet.getString("memory_type")),
                resultSet.getString("memory_key"),
                resultSet.getString("content"),
                resultSet.getString("summary"),
                resultSet.getDouble("importance"),
                resultSet.getDouble("confidence"),
                MemoryStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("source_conversation_id"),
                resultSet.getString("content_hash"),
                resultSet.getString("supersedes_id"),
                readLocalDateTime(resultSet, "expires_at"),
                readLocalDateTime(resultSet, "created_at"),
                readLocalDateTime(resultSet, "updated_at"),
                readLocalDateTime(resultSet, "last_accessed_at"),
                resultSet.getLong("access_count")
        );
    }

    /**
     * 读取 SQLite 时间字段。
     * 字段为空时返回 null。
     */
    private static LocalDateTime readLocalDateTime(
            ResultSet resultSet,
            String columnName
    ) throws SQLException {
        //SQLite JDBC 驱动读取时间字段并转换为 Timestamp
        Timestamp timestamp = resultSet.getTimestamp(columnName);

        if (timestamp == null) {
            return null;
        }

        return timestamp.toLocalDateTime();
    }

    /**
     * 将 LocalDateTime 转换为 JDBC Timestamp。
     * 负责与读取过程相反的转换：
     *  LocalDateTime
     * → Timestamp
     * → SQLite
     * 时间为空时返回 null。
     */
    private static Timestamp toTimestamp(LocalDateTime value) {
        if (value == null) {
            return null;
        }

        return Timestamp.valueOf(value);
    }

    /**
     * 校验准备写入数据库的长期记忆。
     */
    private static void validateMemoryItem(MemoryItem memoryItem) {
        if (memoryItem == null) {
            throw new IllegalArgumentException("memoryItem 不能为空");
        }

        requireText(memoryItem.id(), "记忆 ID");
        requireText(memoryItem.userId(), "用户 ID");
        requireMemoryType(memoryItem.type());
        requireText(memoryItem.content(), "记忆内容");
        requireText(memoryItem.summary(), "记忆摘要");
        requireText(memoryItem.contentHash(), "内容哈希");

        if (memoryItem.status() == null) {
            throw new IllegalArgumentException("记忆状态不能为空");
        }

        validateScore(memoryItem.importance(), "记忆重要程度");
        validateScore(memoryItem.confidence(), "记忆可信程度");

        if (memoryItem.createdAt() == null) {
            throw new IllegalArgumentException("记忆创建时间不能为空");
        }

        if (memoryItem.updatedAt() == null) {
            throw new IllegalArgumentException("记忆更新时间不能为空");
        }

        if (memoryItem.accessCount() < 0) {
            throw new IllegalArgumentException("记忆访问次数不能小于 0");
        }
    }

    /**
     * 统一检查记忆类型是否为空
     */
    private static MemoryType requireMemoryType(MemoryType memoryType) {
        if (memoryType == null) {
            throw new IllegalArgumentException("记忆类型不能为空");
        }

        return memoryType;
    }

    /**
     * 校验必填字符串，并返回去除首尾空格后的内容。
     * requireText
     * → 字段必须存在
     * → 为空就抛异常
     */
    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }

        return value.trim();
    }

    /**
     * 规范化非必填字符串。
     * normalizeOptionalText
     * → 字段允许不存在
     * → 空字符串转换为 null
     */
    private static String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        return value.trim();
    }
}