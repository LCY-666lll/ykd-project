package com.fourth.ykd.ai.dto;

/*已经生成完成、可以发送的文档:可直接发送到微信的生成文件。*/
public record GeneratedDocument(
        byte[] bytes,
        String fileName,
        String contentType
) {
}
