package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.PendingUserFile;

/** 读取文件内容并返回文本摘要，供后续聊天作为上下文。参考 ImageUnderstandingService。 */
public interface FileUnderstandingService {

    String understand(PendingUserFile file);
}
