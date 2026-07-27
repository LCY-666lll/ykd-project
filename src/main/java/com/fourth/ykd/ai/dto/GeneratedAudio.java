package com.fourth.ykd.ai.dto;


/*千问已经合成完成的音频*/
public record GeneratedAudio(
        byte[] bytes,
        String fileName,
        String contentType
) {
}
