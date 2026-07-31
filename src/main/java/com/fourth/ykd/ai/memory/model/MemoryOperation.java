package com.fourth.ykd.ai.memory.model;

/**
 * 记忆提取模型 希望执行的操作。
 * 模型只能提出操作建议，
 * 最终是否执行必须由业务代码校验和决定。
 */
public enum MemoryOperation {

    /**
     * 用户新增、确认或纠正了一项长期事实。
     * 记忆合并模型会结合已有 ACTIVE 记忆，
     * 进一步决定执行 CREATE、CONFIRM、REPLACE 或 IGNORE。
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