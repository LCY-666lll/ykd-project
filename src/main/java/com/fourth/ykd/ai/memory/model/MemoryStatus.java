package com.fourth.ykd.ai.memory.model;

/**
 * 长期记忆当前所处的状态。
 */
public enum MemoryStatus{

    //当前有效的记忆。检索和注入时，只使用 ACTIVE 状态的记忆。
    ACTIVE,

    //已经被新版本替代的旧记忆。旧记忆不会删除，但不会继续注入模型。
    SUPERSEDED,

    //已经被用户删除，或者被系统确认无效的记忆。
    DELETED
}