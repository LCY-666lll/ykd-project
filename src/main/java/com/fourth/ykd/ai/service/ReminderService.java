package com.fourth.ykd.ai.service;

//提醒服务
public interface ReminderService {
    // 根据自然语言创建提醒任务，contextToken 从用户消息中提取用于后续推送
    String createReminder(String userId, String contextToken, String userText);

    // 删除用户所有提醒任务，返回确认语
    String deleteReminder(String userId);
}