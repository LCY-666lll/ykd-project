package com.fourth.ykd.ilink.dto;

/**
 * 发起 iLink 登录后返回给 Controller 的数据。
 * qrCodeContent 是 SDK 返回的原始二维码内容，下一步会被转换成二维码图片。
 */
public record IlinkLoginQrResponse(
        String qrCodeContent,
        String status
) {
}