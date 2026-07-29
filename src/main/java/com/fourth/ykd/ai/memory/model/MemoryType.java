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
    ARTIFACT
}
