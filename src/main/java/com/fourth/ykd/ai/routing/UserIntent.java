package com.fourth.ykd.ai.routing;

/*意图枚举：文本/生图/编辑图/识图*/
public enum UserIntent {
    TEXT,
    IMAGE_GENERATE,
    IMAGE_EDIT,
    IMAGE_UNDERSTAND,
    FILE_GENERATE,
    VOICE_REPLY,
    //创建任务
    CREATE_TASK,
    //删除任务
    DELETE_TASK
}