package com.fourth.ykd.ai.routing;

/*意图枚举：文本/生图/编辑图/识图/文件生成/语音回复/定时任务/周期任务*/
public enum UserIntent {
    TEXT,
    IMAGE_GENERATE,
    IMAGE_EDIT,
    IMAGE_UNDERSTAND,
    FILE_GENERATE,
    VOICE_REPLY,
    TASK_SCHEDULED,
    TASK_PERIODIC
}