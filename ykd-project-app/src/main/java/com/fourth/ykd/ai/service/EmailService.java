package com.fourth.ykd.ai.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 邮件发送服务，封装 JavaMailSender 实现。
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailService(JavaMailSender mailSender,
                        @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /**
     * 发送纯文本邮件。
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件正文
     */
    public void sendSimpleMail(String to, String subject, String content) {
        log.info("[EMAIL][START] 发送邮件 to={}, subject={}", to, subject);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);
            mailSender.send(message);

            log.info("[EMAIL][SUCCESS] 邮件发送成功 to={}", to);
        } catch (Exception e) {
            log.error("[EMAIL][FAILED] 邮件发送失败 to={}, reason={}", to, e.getMessage());
            throw new RuntimeException("邮件发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送带附件的邮件。
     *
     * @param to         收件人邮箱
     * @param subject    邮件主题
     * @param content    邮件正文
     * @param attachment 附件文件路径
     */
    public void sendMailWithAttachment(String to, String subject, String content, String attachment) {
        log.info("[EMAIL][START] 发送带附件邮件 to={}, subject={}, attachment={}", to, subject, attachment);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content);

            File file = new File(attachment);
            if (file.exists()) {
                FileSystemResource resource = new FileSystemResource(file);
                helper.addAttachment(file.getName(), resource);
            }

            mailSender.send(message);
            log.info("[EMAIL][SUCCESS] 带附件邮件发送成功 to={}", to);
        } catch (MessagingException e) {
            log.error("[EMAIL][FAILED] 带附件邮件发送失败 to={}, reason={}", to, e.getMessage());
            throw new RuntimeException("邮件发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送 HTML 格式邮件。
     *
     * @param to         收件人邮箱
     * @param subject    邮件主题
     * @param htmlContent HTML 内容
     */
    public void sendHtmlMail(String to, String subject, String htmlContent) {
        log.info("[EMAIL][START] 发送HTML邮件 to={}, subject={}", to, subject);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("[EMAIL][SUCCESS] HTML邮件发送成功 to={}", to);
        } catch (MessagingException e) {
            log.error("[EMAIL][FAILED] HTML邮件发送失败 to={}, reason={}", to, e.getMessage());
            throw new RuntimeException("邮件发送失败: " + e.getMessage(), e);
        }
    }
}