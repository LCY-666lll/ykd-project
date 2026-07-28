package com.fourth.ykd.ai.dto;

public class ReminderTask {
    private String id;
    // 用户Id（iLink发送方ID）
    private String userId;
    // 提醒内容
    private String content;
    // 一次性提醒的时刻（毫秒时间戳），与 cronExpression 二选一
    private Long triggerTimeMs;
    // 重复提醒的 Cron 表达式，与 triggerTimeMs 二选一
    private String cronExpression;
    // 提醒发送需要的 iLink contextToken（从用户消息中提取并持久化，避免 SDK 内部缓存失效）
    private String contextToken;
    // 创建时间
    private String createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getTriggerTimeMs() {
        return triggerTimeMs;
    }

    public void setTriggerTimeMs(Long triggerTimeMs) {
        this.triggerTimeMs = triggerTimeMs;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getContextToken() {
        return contextToken;
    }

    public void setContextToken(String contextToken) {
        this.contextToken = contextToken;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}