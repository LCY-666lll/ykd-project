package com.fourth.ykd.ai.memory.policy;

import com.fourth.ykd.ai.memory.model.MemoryCandidate;
import com.fourth.ykd.ai.memory.model.MemoryOperation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * 长期记忆候选项处理策略。
 * 该类负责必要的数据边界校验，不判断对话内容的业务含义
 *       通过该策略的候选项才允许进入长期记忆保存流程。
 */
@Component
public class MemoryCandidatePolicy {

    private static final int MAX_KEY_LENGTH = 160;
    private static final int MAX_CONTENT_LENGTH = 2_000;
    private static final int MAX_SUMMARY_LENGTH = 300;

    /**
     * 准备一条可以交给长期记忆服务处理的候选项。
     * 返回空表示模型没有产生有效的记忆操作。
     */
    public Optional<MemoryCandidate> prepare(MemoryCandidate candidate){
        if (candidate==null || candidate.type()==null || candidate.operation()==null){
            return Optional.empty();
        }
        //Java只执行模型给出的操作决定
        return switch (candidate.operation()){
            case UPSERT -> prepareUpsert(candidate);
            case DELETE -> prepareDelete(candidate);
            case NO_CHANGE -> Optional.empty();
        };
    }

    private Optional<MemoryCandidate> prepareDelete(MemoryCandidate candidate) {
        String memoryKey = normalizeKey(candidate.memoryKey());
        if (!StringUtils.hasText(memoryKey) || isTooLong(memoryKey,MAX_CONTENT_LENGTH)){
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
                || !isValidScore(candidate.confidence())) {
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
