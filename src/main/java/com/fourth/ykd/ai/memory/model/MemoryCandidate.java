package com.fourth.ykd.ai.memory.model;

import java.time.LocalDateTime;

/**
 * 从一轮对话中提取出来的 长期记忆候选项。
 * 该对象只表示 模型的提取结果，
 * 未经过业务校验，也未写入数据库。
 * 模型以后负责输出类似：
 * new MemoryCandidate(
 *         MemoryType.PROJECT,
 *         "project.ykd.active_branch",
 *         "ykd-project 后续开发使用 lcy-project 分支",
 *         "ykd-project 使用 lcy-project 分支",
 *         0.9,
 *         0.98,
 *         MemoryOperation.UPSERT,
 *         null
 *  );
 */
public record MemoryCandidate(

        /**
         * 记忆类型。
         */
        MemoryType type,

        /**
         * 记忆的稳定业务键。
         * PROFILE、PREFERENCE、PROJECT 等可替换事实应尽量提供。
         * EPISODE、ARTIFACT 等追加型记忆可以为空。
         * 例如：
         * profile.name
         * preference.answer_style
         * project.ykd.active_branch
         */
        String memoryKey,

        /**
         * 需要长期保存的完整事实内容。
         */
        String content,

        /**
         * 用于检索和注入模型的简短摘要。
         */
        String summary,

        /**
         * 记忆的重要程度，取值范围为 0 到 1。
         */
        double importance,

        /**
         * 模型对提取结果的可信程度，取值范围为 0 到 1。
         */
        double confidence,

        /**
         * 模型建议执行的记忆操作。
         */
        MemoryOperation operation,

        /**
         * 记忆过期时间。
         * 永久有效的记忆使用 null。
         */
        LocalDateTime expiresAt
) {
}