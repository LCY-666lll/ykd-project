package com.fourth.ykd.ai.dto;

/*iLink 发送图片时最终需要的是：
图片字节 文件名 图片媒体类型*/
public record GeneratedImage(
        byte[] bytes,
        String fileName,
        String contentType
) {
}