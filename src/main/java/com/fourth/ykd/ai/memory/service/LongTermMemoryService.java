package com.fourth.ykd.ai.memory.service;

import com.fourth.ykd.ai.memory.model.MemoryCandidate;
import com.fourth.ykd.ai.memory.model.MemoryConsolidationResult;
import com.fourth.ykd.ai.memory.model.MemoryIndexOutboxTask;
import com.fourth.ykd.ai.memory.model.MemoryItem;
import com.fourth.ykd.ai.memory.model.MemoryOperation;
import com.fourth.ykd.ai.memory.model.MemoryStatus;
import com.fourth.ykd.ai.memory.model.MemoryWriteResult;
import com.fourth.ykd.ai.memory.policy.MemoryCandidatePolicy;
import com.fourth.ykd.ai.memory.repository.MemoryIndexOutboxRepository;
import com.fourth.ykd.ai.memory.repository.SqliteLongTermMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责把已经通过 AI 提取和语义合并的记忆决定可靠写入 SQLite。
 * 提取模型负责生成候选，合并模型负责给出 CREATE、CONFIRM、REPLACE、DELETE 或 IGNORE；
 * 本服务不重新判断对话语义，只负责用户隔离、目标校验、内容哈希兜底去重、版本化和事务一致性。
 * 外部调用 applyDecision
 *         ↓
 * 校验并清理 userId
 *         ↓
 * candidatePolicy.prepare(candidate)
 *         ↓
 * 候选无效？
 *     是 → IGNORED
 *     否
 *         ↓
 * 根据 decision.action 分流
 */
@Service
@RequiredArgsConstructor
public class LongTermMemoryService {

    //负责候选字段长度、评分和空值等数据库边界校验
    private final MemoryCandidatePolicy candidatePolicy;
    //负责 agent_memory 表的实际查询和状态更新
    private final SqliteLongTermMemoryRepository memoryRepository;
    private final MemoryIndexOutboxRepository memoryIndexOutboxRepository;

    /**
     * 执行一条经过语义合并的长期记忆决定。
     * 整个方法处于同一事务中，替换时“停用旧版本”和“插入新版本”必须同时成功或同时回滚。
     *
     * @param userId 当前微信用户 ID
     * @param conversationId 产生本次记忆的会话 ID
     * @param candidate 已通过提取模型生成的候选记忆
     * @param decision 已通过合并服务安全校验的处理决定
     * @return 实际执行结果；无效候选或 IGNORE 返回 IGNORED
     */
    @Transactional
    public MemoryWriteResult applyDecision(
            String userId,
            String conversationId,
            MemoryCandidate candidate,
            MemoryConsolidationResult.Decision decision
    ) {
        String normalizedUserId = requireUserId(userId);

        if (decision == null
                || decision.action() == MemoryConsolidationResult.Action.IGNORE) {
            return ignored();
        }

        Optional<MemoryCandidate> prepared =
                candidatePolicy.prepare(candidate);

        if (prepared.isEmpty()) {
            return ignored();
        }

        MemoryCandidate normalizedCandidate = prepared.get();
        validateDecisionAction(
                normalizedCandidate.operation(),
                decision.action()
        );

        return switch (decision.action()) {
            case CREATE -> createByDecision(
                    normalizedUserId,
                    normalizeOptionalText(conversationId),
                    normalizedCandidate,
                    decision.targetMemoryIds()
            );
            case CONFIRM -> confirmTargets(
                    normalizedUserId,
                    normalizedCandidate,
                    decision.targetMemoryIds()
            );
            case REPLACE -> replaceTargets(
                    normalizedUserId,
                    normalizeOptionalText(conversationId),
                    normalizedCandidate,
                    decision.targetMemoryIds()
            );
            case DELETE -> deleteTargets(
                    normalizedUserId,
                    normalizedCandidate,
                    decision.targetMemoryIds()
            );
            case IGNORE -> ignored();
        };
    }

    /**
     * 执行 CREATE 决定。
     * CREATE 不允许携带旧记忆 ID；写入前再使用同类型内容哈希做一次兜底去重，
     * 防止模型漏判完全相同内容时产生重复 ACTIVE 记录。
     */
    private MemoryWriteResult createByDecision(
            String userId,
            String conversationId,
            MemoryCandidate candidate,
            List<String> targetMemoryIds
    ) {
        requireNoTargetMemoryIds(targetMemoryIds);

        String contentHash = calculateContentHash(candidate.content());
        Optional<MemoryItem> duplicate =
                memoryRepository.findActiveByContentHash(
                        userId,
                        candidate.type(),
                        contentHash
                );

        if (duplicate.isPresent()) {
            return confirm(duplicate.get(), candidate);
        }

        return create(
                userId,
                conversationId,
                candidate,
                contentHash,
                null,
                MemoryWriteResult.Action.CREATED
        );
    }

    /**
     * 执行 CONFIRM 决定。
     * 多条重复目标只保留更新时间最新的一条，其余标记为 SUPERSEDED；
     * PROFILE 与 PREFERENCE 类型不同，即使语义相同也不能直接确认，必须通过 REPLACE 纠正类型。
     */
    private MemoryWriteResult confirmTargets(
            String userId,
            MemoryCandidate candidate,
            List<String> targetMemoryIds
    ) {
        List<MemoryItem> targets = loadActiveTargets(
                userId,
                candidate,
                targetMemoryIds
        );
        if (targets.stream().anyMatch(target ->
                target.type() != candidate.type()
        )) {
            throw new IllegalStateException(
                    "不同类型的长期记忆不能直接确认"
            );
        }
        MemoryItem retained = findLatestMemory(targets);

        for (MemoryItem target : targets) {
            if (!target.id().equals(retained.id())) {
                markSupersededAndQueueDelete(target.id());
            }
        }

        return confirm(retained, candidate);
    }

    /**
     * 执行 REPLACE 决定。
     * 先把合并模型命中的所有旧记录标记为 SUPERSEDED，再创建一条新的 ACTIVE 版本；
     * 新记录的 supersedesId 指向这些目标中更新时间最新的一条。
     */
    private MemoryWriteResult replaceTargets(
            String userId,
            String conversationId,
            MemoryCandidate candidate,
            List<String> targetMemoryIds
    ) {
        List<MemoryItem> targets = loadActiveTargets(
                userId,
                candidate,
                targetMemoryIds
        );
        MemoryItem latest = findLatestMemory(targets);

        for (MemoryItem target : targets) {
            markSupersededAndQueueDelete(target.id());
        }

        return create(
                userId,
                conversationId,
                candidate,
                calculateContentHash(candidate.content()),
                latest.id(),
                MemoryWriteResult.Action.REPLACED
        );
    }

    /**
     * 执行 DELETE 决定。
     * 所有命中的目标都执行软删除并保留历史，返回值携带其中更新时间最新的删除记录。
     */
    private MemoryWriteResult deleteTargets(
            String userId,
            MemoryCandidate candidate,
            List<String> targetMemoryIds
    ) {
        List<MemoryItem> targets = loadActiveTargets(
                userId,
                candidate,
                targetMemoryIds
        );
        MemoryItem latest = findLatestMemory(targets);

        for (MemoryItem target : targets) {
            markDeletedAndQueueDelete(target.id());
        }

        MemoryItem deleted = memoryRepository
                .findById(latest.id())
                .orElse(latest);

        return new MemoryWriteResult(
                MemoryWriteResult.Action.DELETED,
                deleted
        );
    }

    /**
     * 根据合并模型返回的数据库 ID 加载并校验全部目标记忆。
     * 需要目标的操作如果没有提供 ID 会立即失败，防止模糊修改数据库。
     */
    private List<MemoryItem> loadActiveTargets(
            String userId,
            MemoryCandidate candidate,
            List<String> targetMemoryIds
    ) {
        if (targetMemoryIds == null || targetMemoryIds.isEmpty()) {
            throw new IllegalStateException(
                    "当前记忆操作缺少目标记忆编号"
            );
        }

        return targetMemoryIds.stream()
                .map(memoryId -> loadActiveTarget(
                        userId,
                        candidate,
                        memoryId
                ))
                .toList();
    }

    /**
     * 加载并校验单条目标记忆。
     * 目标必须真实存在、属于当前用户、仍为 ACTIVE，并且类型与候选兼容；
     * PROFILE/PREFERENCE 可以互相纠错，其他类型必须完全一致。
     */
    private MemoryItem loadActiveTarget(
            String userId,
            MemoryCandidate candidate,
            String memoryId
    ) {
        MemoryItem target = memoryRepository
                .findById(memoryId)
                .orElseThrow(() ->
                        new IllegalStateException("目标长期记忆不存在")
                );

        if (!Objects.equals(userId, target.userId())) {
            throw new IllegalStateException(
                    "不能修改其他用户的长期记忆"
            );
        }

        if (target.status() != MemoryStatus.ACTIVE) {
            throw new IllegalStateException(
                    "目标长期记忆已不再有效"
            );
        }

        if (!candidate.type().isConsolidationCompatibleWith(
                target.type()
        )) {
            throw new IllegalStateException(
                    "候选记忆和目标记忆类型不兼容"
            );
        }

        return target;
    }

    /**
     * 校验合并模型的 Action 不能违背提取候选的原始 operation。
     * UPSERT 只能创建、确认或替换，DELETE 只能删除，NO_CHANGE 不允许进入写库。
     */
    private void validateDecisionAction(
            MemoryOperation operation,
            MemoryConsolidationResult.Action action
    ) {
        boolean valid = switch (operation) {
            case UPSERT -> action == MemoryConsolidationResult.Action.CREATE
                    || action == MemoryConsolidationResult.Action.CONFIRM
                    || action == MemoryConsolidationResult.Action.REPLACE;
            case DELETE -> action == MemoryConsolidationResult.Action.DELETE;
            case NO_CHANGE -> false;
        };

        if (!valid) {
            throw new IllegalStateException(
                    "记忆合并决定与候选操作不一致"
            );
        }
    }

    /**
     * 从多条目标记忆中选出更新时间最新的一条，作为确认保留记录或版本关系来源。
     */
    private MemoryItem findLatestMemory(List<MemoryItem> memories) {
        return memories.stream()
                .max(Comparator.comparing(
                        MemoryItem::updatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .orElseThrow(() ->
                        new IllegalStateException("没有可以操作的目标记忆")
                );
    }

    /**
     * 校验 CREATE 决定不能携带已有记忆 ID。
     * 携带目标意味着模型给出的 Action 与目标关系不一致，必须拒绝执行。
     */
    private void requireNoTargetMemoryIds(
            List<String> targetMemoryIds
    ) {
        if (targetMemoryIds != null && !targetMemoryIds.isEmpty()) {
            throw new IllegalStateException(
                    "创建记忆时不允许携带目标记忆编号"
            );
        }
    }
    /**
     * 相同事实再次出现时，提高确认信息，不产生重复记录。
     */
    private MemoryWriteResult confirm(
            MemoryItem current,
            MemoryCandidate candidate
    ) {
        requireOneRow(memoryRepository.refreshConfirmation(
                current.id(),
                candidate.importance(),
                candidate.confidence()
        ));

        //数据库修改完成后，重新查询这条记忆
        MemoryItem refreshed = memoryRepository
                .findById(current.id())
                .orElse(current);

        return new MemoryWriteResult(
                MemoryWriteResult.Action.CONFIRMED,
                refreshed
        );
    }

    /**
     * 统一创建一条状态为 ACTIVE 的正式长期记忆
     */
    private MemoryWriteResult create(
            String userId,
            String conversationId,
            MemoryCandidate candidate,
            String contentHash,
            String supersedesId,
            MemoryWriteResult.Action action
    ) {
        LocalDateTime now = LocalDateTime.now();

        MemoryItem memory = new MemoryItem(
                UUID.randomUUID().toString(),
                userId,
                candidate.type(),
                candidate.memoryKey(),
                candidate.content(),
                candidate.summary(),
                candidate.importance(),
                candidate.confidence(),
                MemoryStatus.ACTIVE,
                conversationId,
                contentHash,
                supersedesId,
                candidate.expiresAt(),
                now,
                now,
                null,
                0
        );

        /*1. memoryRepository.insert(memory)
        2. Repository 返回影响行数
        3. requireOneRow 检查是不是 1*/
        requireOneRow(memoryRepository.insert(memory));

        memoryIndexOutboxRepository.enqueue(
                memory.id(),
                MemoryIndexOutboxTask.Operation.UPSERT
        );

        /*action 是外部传进来的。
        普通新增传：CREATED
        替换旧版本传：REPLACED
        同一个 create() 方法既能服务新增，也能服务替换*/
        return new MemoryWriteResult(action, memory);
    }

    /**
     * 生成稳定内容哈希，用于判断内容是否完全相同。
     */
    private void markSupersededAndQueueDelete(String memoryId) {
        requireOneRow(memoryRepository.markSuperseded(memoryId));
        memoryIndexOutboxRepository.enqueue(
                memoryId,
                MemoryIndexOutboxTask.Operation.DELETE
        );
    }

    private void markDeletedAndQueueDelete(String memoryId) {
        requireOneRow(memoryRepository.markDeleted(memoryId));
        memoryIndexOutboxRepository.enqueue(
                memoryId,
                MemoryIndexOutboxTask.Operation.DELETE
        );
    }

    private String calculateContentHash(String content) {
        try {
            //MessageDigest 是 Java 提供的摘要算法工具类
            byte[] hash = MessageDigest
                    .getInstance("SHA-256")
            //哈希算法不能直接处理 Java 字符串，处理的是字节。先转换成 UTF-8 字节数组
                    .digest(content.getBytes(StandardCharsets.UTF_8));

            //把字节数组转换成类似这样的字符串：3d4f85b7a9c1...
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前 Java 环境不支持 SHA-256"
            );
        }
    }

    /*创建一个“没有执行写入”的结果:
    候选记忆没有通过策略校验 / AI 明确判断不需要修改 / 删除时找不到对应数据 */
    private MemoryWriteResult ignored() {
        return new MemoryWriteResult(
                MemoryWriteResult.Action.IGNORED,
                null
        );
    }
    /**
     * 检查：
     * Repository 的单条新增、修改、删除操作，是不是真的刚好影响了一行
     * 否则抛出异常，让当前事务回滚。
     */
    private void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "长期记忆数据库写入结果异常"
            );
        }
    }

    /**
     * 校验并清理微信用户 ID，保证所有数据库操作都具有明确用户边界。
     */
    private String requireUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException(
                    "用户 ID 不能为空"
            );
        }
        return userId.trim();
    }

    /**
     * 清理允许为空的文本字段，空白字符串统一转换成 null。
     */
    private String normalizeOptionalText(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }

}