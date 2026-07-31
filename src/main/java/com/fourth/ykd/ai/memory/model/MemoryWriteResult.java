package com.fourth.ykd.ai.memory.model;

/**
 * 一次长期记忆写入操作的结果。
 * @param action 本次实际执行的操作
 * @param memory 与本次操作相关的记忆；忽略操作时可以为空
 */
public record MemoryWriteResult(
        Action action,
        MemoryItem memory
) {

    /**
     * 长期记忆写入操作的实际结果。
     */
    public enum Action {

        /**
         * 创建了一条全新的长期记忆。
         */
        CREATED,

        /**
         * 已存在相同记忆，本次只更新了确认信息。
         */
        CONFIRMED,

        /**
         * 新记忆替代了合并模型命中的一条或多条旧版本。
         */
        REPLACED,

        /**
         * 已有记忆被标记为删除。
         */
        DELETED,

        /**
         * 当前候选项不需要写入数据库。
         */
        IGNORED
    }
}