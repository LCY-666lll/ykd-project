package com.fourth.ykd.ai.utils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import com.github.wechat.ilink.sdk.ILinkClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 二维码生成工具，提供给大模型调用。
 */
@Slf4j
@Component
public class QrCodeTool {

    private static final int DEFAULT_WIDTH = 300;
    private static final int DEFAULT_HEIGHT = 300;

    private final IlinkClientManager clientManager;

    public QrCodeTool(IlinkClientManager clientManager) {
        this.clientManager = clientManager;
    }

    @Tool(name = "generate_qr_code", description = """
            生成二维码图片并发送给用户。
            适用场景：用户要求生成二维码、把文字/链接转为二维码、制作二维码图片。
            输入内容可以是：网址URL、微信号、手机号、任意文本。
            会自动将生成的二维码图片发送给用户。
            用户说把什么什么生成二维码，生成的内容就是什么什么
            """)
    public String generateQrCode(
            @ToolParam(description = "要编码到二维码中的内容（网址、文字、微信号等）", required = true) String content
    ) {
        log.info("[AI][TOOL][QRCODE][START] content={}", content);

        if (content == null || content.isBlank()) {
            return "错误：内容不能为空";
        }

        // 获取客户端
        ILinkClient client = clientManager.findClient().orElse(null);
        if (client == null) {
            return "错误：微信客户端未初始化";
        }

        try {
            // 生成二维码
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            // 处理内容：如果不是URL，包装为可显示文本的格式
            String qrContent = prepareContent(content.trim());

            BitMatrix bitMatrix = qrCodeWriter.encode(
                    qrContent,
                    BarcodeFormat.QR_CODE,
                    DEFAULT_WIDTH,
                    DEFAULT_HEIGHT,
                    hints
            );

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
            byte[] qrCodeBytes = outputStream.toByteArray();

            // 发送图片给用户
            String userId = ScheduledTaskTool.getCurrentUserId();// 复用 ScheduledTaskTool 中已有的用户ID管理：
            if (userId != null) {
                // 刷新消息队列
                try {
                    client.getUpdates();
                } catch (Exception ignored) {
                }
                client.sendImage(userId, qrCodeBytes, "qrcode.png", null);
                log.info("[AI][TOOL][QRCODE][SUCCESS] 二维码已发送, content={}, userId={}, bytes={}", content, userId, qrCodeBytes.length);

                // 根据内容类型返回不同的提示
                boolean isUrl = content.startsWith("http://") || content.startsWith("https://");
                if (isUrl) {
                    return "二维码已生成并发送给您啦！\n内容是：" + content + "\n\n📱 扫码即可打开链接";
                } else {
                    return "二维码已生成并发送给您啦！\n内容是：" + content + "\n\n📱 请使用手机相机或浏览器扫码查看，微信扫码可能无法直接显示文本内容";
                }
            } else {
                log.warn("[AI][TOOL][QRCODE][WARN] 无法获取用户ID，content={}", content);
                return "二维码已生成，但无法发送（未获取到用户ID）";
            }
        } catch (WriterException | IOException e) {
            log.error("[AI][TOOL][QRCODE][FAILED] content={}, reason={}", content, e.getMessage());
            return "二维码生成失败: " + e.getMessage();
        }
    }

    /**
     * 处理二维码内容：URL保持原样，纯文本直接使用
     */
    private String prepareContent(String content) {
        // 如果是URL，直接返回
        if (content.startsWith("http://") || content.startsWith("https://")) {
            return content;
        }

        // 如果是电话号码
        if (content.matches("^1[3-9]\\d{9}$")) {
            return "tel:" + content;
        }

        // 如果是邮箱
        if (content.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
            return "mailto:" + content;
        }

        // 纯文本直接返回
        return content;
    }
}