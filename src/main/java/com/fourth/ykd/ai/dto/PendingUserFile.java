package com.fourth.ykd.ai.dto;

import java.time.Instant;

/** 用户刚发来的待处理文件，带接收时间戳用于过期清理。参考 PendingUserImage。 */
public record PendingUserFile(
        byte[] bytes,
        String fileName,
        String contentType,
        Instant receivedAt
) {}
