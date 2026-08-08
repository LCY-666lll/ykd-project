package com.fourth.ykd.ai.memory.model;

/**
 * 长期记忆类型。
 * 只有属于这些类型的信息，才允许进入长期记忆数据库。
 */
public enum MemoryType {

    //用户的稳定资料：用户姓名，职业，学习方向等
    PROFILE,

    //用户的个人偏好：回答风格，内容详细程度等
    PREFERENCE,

    //用户规定的长期事实；决定内容，开发计划
    PROJECT,

    //用户当前正在处理的任务目标：长期记忆中的任务事实
    TASK,

    //值得长期保存的重要历史事件：用户完成某个决定或重构等
    EPISODE,

    //图片、文件等产物的文字描述：用户发送过的图片、生成的图片
    ARTIFACT;

    /**
     * 判断两个记忆类型能否进入同一次语义合并。
     * PROFILE 与 PREFERENCE 允许互相纠正错误分类，其他类型仍严格隔离。
     * @param other 已有长期记忆的类型
     * @return 类型相同，或双方属于 PROFILE/PREFERENCE 时返回 true
     */
    public boolean isConsolidationCompatibleWith(MemoryType other) {
        //相同类型始终可以进入同一次合并比较。
        if (this == other) {
            return true;
        }
        //只有 PROFILE 和 PREFERENCE 可以跨类型纠正，其他组合返回 false。
        return (this == PROFILE || this == PREFERENCE)
                && (other == PROFILE || other == PREFERENCE);
    }
}
