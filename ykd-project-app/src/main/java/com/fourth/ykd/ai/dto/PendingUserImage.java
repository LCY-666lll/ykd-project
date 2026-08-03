package com.fourth.ykd.ai.dto;

import java.time.Instant;

/*用户发来、正在等待处理的原始图片:
为什么还需要 receivedAt
图片不能永久留在内存中。
如果用户发图后不再发送消息，而系统没有过期时间：
ConcurrentHashMap
→ 永远保留图片 byte[]
→ 用户越多，占用内存越多
所以记录接收时间：
Instant receivedAt*/
public record   PendingUserImage(
        byte[] bytes,
        String contentType,
        Instant receivedAt
) {
}