package com.fourth.ykd.ai.utils;

import com.fourth.ykd.ai.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 邮件发送工具，提供给大模型调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailTool {

    private final EmailService emailService;

    @Tool(name = "send_email", description = """
            通过邮箱发送邮件。
            适用场景：用户明确说发邮件、写邮件、发信，或提供邮箱地址（含@符号）。
            特征：正式通知、求职简历、带附件、商务邮件、邮箱地址（如xxx@qq.com）,
            必须提供：收件人邮箱地址、邮件主题、邮件内容。
            """)
    public String sendEmail(
            @ToolParam(description = "收件人邮箱地址，如 example@qq.com", required = true) String to,
            @ToolParam(description = "邮件主题/标题", required = true) String subject,
            @ToolParam(description = "邮件正文内容", required = true) String content,
            @ToolParam(description = "附件文件路径（可选，不传则发送纯文本邮件）", required = false) String attachment
    ) {
        log.info("[AI][TOOL][EMAIL][START] to={}, subject={}", to, subject);

        // 参数校验
        if (to == null || to.isBlank()) {
            return "错误：收件人邮箱地址不能为空";
        }
        if (!to.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
            return "错误：邮箱地址格式不正确，请提供有效的邮箱地址";
        }
        if (subject == null || subject.isBlank()) {
            return "错误：邮件主题不能为空";
        }
        if (content == null || content.isBlank()) {
            return "错误：邮件内容不能为空";
        }

        try {
            if (attachment != null && !attachment.isBlank()) {
                emailService.sendMailWithAttachment(to.trim(), subject.trim(), content.trim(), attachment.trim());
            } else {
                emailService.sendSimpleMail(to.trim(), subject.trim(), content.trim());
            }

            String result = "邮件发送成功！\n收件人: " + to + "\n主题: " + subject;
            log.info("[AI][TOOL][EMAIL][SUCCESS] to={}", to);
            return result;
        } catch (Exception e) {
            log.error("[AI][TOOL][EMAIL][FAILED] to={}, reason={}", to, e.getMessage());
            return "邮件发送失败: " + e.getMessage();
        }
    }
}