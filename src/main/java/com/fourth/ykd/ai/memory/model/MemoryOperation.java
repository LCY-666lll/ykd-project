package com.fourth.ykd.ai.memory.model;

/**
 * 记忆提取模型 希望执行的操作。
 * 模型只能提出操作建议，
 * 最终是否执行必须由业务代码校验和决定。
 */
public enum MemoryOperation {

    /**
     * 新增一条记忆，或者更新已有记忆。
     * 最终是插入新记录还是替换旧版本，
     * 由 LongTermMemoryService 根据数据库现状判断。
     */
    UPSERT,

    /**
     * 删除一条已有记忆。
     * 实际处理时使用软删除，不直接删除数据库记录。
     */
    DELETE,

    /**
     * 当前对话不需要修改长期记忆。
     */
    NO_CHANGE
}