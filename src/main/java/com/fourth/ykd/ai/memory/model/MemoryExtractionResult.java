package com.fourth.ykd.ai.memory.model;

import java.util.List;
import java.util.Objects;

/**
 * 记忆提取模型对一轮完整对话给出的结构化结果。模型输出协议，不包含业务逻辑。
 * 创建一个叫 MemoryExtractionResult 的数据类型，里面只装一个字段：candidates
 * 一轮对话可能形成多条长期记忆，
 * 因此使用候选项集合，而不是单个 MemoryCandidate。
 */
public record MemoryExtractionResult(List<MemoryCandidate> candidates) {
    /**
     * 模型没有返回 candidates 时统一转换成空集合，
     * 避免业务层反复进行 null 判断。
     */
    public MemoryExtractionResult {
        candidates = candidates == null
                //null 统一转换为空集合，避免业务层出现空指针
                ? List.of()
                //非空集合，则使用 List.copyOf() 创建不可修改副本，防止外部代码修改提取结果
                : candidates.stream()
                        .filter(Objects::nonNull)
                        .toList();
    }
}