package com.fourth.ykd.ai.routing;

/*微信消息经过 AI 路由后允许进入的业务分支。
其中 MEMORY_MANAGE 专门处理需要同步确认写库结果的明确长期记忆命令。*/
public enum UserIntent {
    TEXT,
    MEMORY_MANAGE,
    IMAGE_GENERATE,
    IMAGE_EDIT,
    IMAGE_UNDERSTAND,
    FILE_GENERATE,
    VOICE_REPLY
}