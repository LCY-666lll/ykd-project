package com.fourth.ykd.ai.memory.policy;

import com.fourth.ykd.ai.memory.model.MemoryCandidate;
import com.fourth.ykd.ai.memory.model.MemoryOperation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * 长期记忆候选项处理策略。
 * AI 提取出来的 MemoryCandidate 不直接相信，此类负责做最后一层数据库边界校验和规范化。
 * 该类负责必要的数据边界校验，不判断对话内容的业务含义
 *       通过该策略的候选项才允许进入长期记忆保存流程。
 */
@Component
public class MemoryCandidatePolicy {

    //限制模型输出字段长度，避免异常内容直接进入 SQLite 或占用过多模型上下文。
    private static final int MAX_KEY_LENGTH = 160;
    private static final int MAX_CONTENT_LENGTH = 2_000;
    private static final int MAX_SUMMARY_LENGTH = 300;
    //低于该可信度的候选直接忽略，不把模型自己都不确定的事实保存下来。
    private static final double MIN_CONFIDENCE = 0.75;

    /**
     * 准备一条可以交给长期记忆服务处理的候选项。
     * 返回空表示模型没有产生有效的记忆操作。
     */
    public Optional<MemoryCandidate> prepare(MemoryCandidate candidate){
        if (candidate==null || candidate.type()==null || candidate.operation()==null){
            return Optional.empty();
        }
        /* Java只执行模型给出的操作决定
        Policy 的 switch -> 校验并规范化不同操作的数据
        */
        return switch (candidate.operation()){
            case UPSERT -> prepareUpsert(candidate);
            case DELETE -> prepareDelete(candidate);
            case NO_CHANGE -> Optional.empty();
        };
    }

    /**
     * 准备删除候选项。这个方法处理：DELETE 候选
     * 删除操作允许 memoryKey 为空，因为进入该方法前，合并模型已经根据候选语义选定数据库 ID。
     * 该层只保留删除所需的类型和可选 key，不执行数据库查询。
     * @param candidate 记忆提取模型返回的删除候选
     * @return 通过长度校验后的删除候选；key 超长时返回空
     */
    private Optional<MemoryCandidate> prepareDelete(MemoryCandidate candidate) {
        String memoryKey = normalizeKey(candidate.memoryKey());
        if (isTooLong(memoryKey,MAX_KEY_LENGTH)){
            return Optional.empty();
        }
        return Optional.of(new MemoryCandidate(
                candidate.type(),
                memoryKey,
                null,null, 0,0,
                MemoryOperation.DELETE,
                null
        ));
    }

    /**
     * 校验新增或更新候选项。
     */
    private Optional<MemoryCandidate> prepareUpsert(MemoryCandidate candidate){
        String memoryKey = normalizeKey(candidate.memoryKey());
        String content = normalizeText(candidate.content());
        String summary = normalizeText(candidate.summary());

        // 这里只保护数据库边界，不使用关键词判断内容是否值得记忆。
        if (!StringUtils.hasText(content)
                || !StringUtils.hasText(summary)
                || isTooLong(memoryKey, MAX_KEY_LENGTH)
                || content.length() > MAX_CONTENT_LENGTH
                || summary.length() > MAX_SUMMARY_LENGTH
                || !isValidScore(candidate.importance())
                || !isValidScore(candidate.confidence())
                //不允许模型自己标记为低可信度的内容进入数据库。
                || candidate.confidence() < MIN_CONFIDENCE) {
            return Optional.empty();
        }

        return Optional.of(new MemoryCandidate(
                candidate.type(),
                memoryKey,
                content,
                summary,
                candidate.importance(),
                candidate.confidence(),
                MemoryOperation.UPSERT,
                candidate.expiresAt()
        ));
    }


    /**
     * 统一 memoryKey 的格式。
     * 空 key 保留为 null，非空 key 去除首尾空格并按固定区域规则转换为小写。
     */
    private String normalizeKey(String memoryKey){
        if (!StringUtils.hasText(memoryKey)){
            return null;
        }
        //Locale.ROOT:按固定、与国家语言无关的规则转换为小写，保证不同电脑上的结果一致
        return memoryKey.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 清理模型输出文本，但不修改文本本身的含义。
     */
    private String normalizeText(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    /**
     * 校验模型评分必须是 0 到 1 之间的有限数字。
     */
    private boolean isValidScore(double score){
        //判断 score 是不是一个真实、有限、可用的正常数字,且包含边界地处于 [0, 1] 范围内
        return Double.isFinite(score) && score>=0 && score<=1;
    }

    /**
     * 判断一个可选字符串字段是否超过最大长度。字段是 null 时，直接认为“不超长”。
     */
    private boolean isTooLong(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

}
