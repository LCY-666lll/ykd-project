package com.fourth.ykd.ilink.service;

import com.fourth.ykd.ai.service.ImageContextService;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/*定时轮询 iLink 消息。负责 client.getUpdates()、过滤机器人自己的消息、提取文本/语音/图片。*/
@Slf4j
@Service
@RequiredArgsConstructor
public class IlinkMessagePollingService {
    private final IlinkClientManager clientManager;
    private final IlinkMessageReplyService ilinkMessageReplyService;
    private final ImageContextService imageContextService;

    /** 每 500ms 轮询一次：fixedDelay = 上次执行结束到下次开始，任务慢也不会重叠 */
    @Scheduled(fixedDelayString = "${ilink.poll-delay-ms:500}")
    public void pollMessages() {
        // 链式 Optional 一行处理三种状态：
        // 没客户端 → 空；有但未登录 → filter 过滤掉；已登录 → 拉消息
        // 这一行就是"iLink 未登录时系统静默跳过"的实现
        clientManager.findClient()
                .filter(ILinkClient::isLoggedIn)
                .ifPresent(this::pullMessages);
    }
    private void pullMessages(ILinkClient client) {
        try {
            // getUpdates() 是长轮询：会挂住等待，直到有新消息或 35s 超时
            List<WeixinMessage> messages = client.getUpdates();
            for (WeixinMessage message : messages) {
                handleMessage(client, message);
            }
        } catch (IOException exception) {
            log.warn("[iLink][RECEIVE_FAILED] {}", exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("[iLink][RECEIVE_INTERRUPTED] {}", exception.getMessage());
        }
    }

    private void handleMessage(ILinkClient client, WeixinMessage message) {
        String fromUserId = message.getFrom_user_id();
        // 第一道过滤：没有用户 ID，或消息来自机器人自己 → 丢弃
        // 防止机器人回复自己的消息再次进入业务，形成"自己回复自己"的死循环
        if (!StringUtils.hasText(fromUserId) || isFromBot(client, fromUserId)) {
            return;
        }

        String text = extractText(message);
        String voiceText = extractVoiceText(message);
        MessageItem imageItem = extractImageItem(message);
        if (imageItem != null) {
            saveImageContext(client, fromUserId, imageItem);
        }
        if (StringUtils.hasText(voiceText)) {
            log.info("[iLink][VOICE_RECOGNIZED] fromUserId={}, text={}", fromUserId, voiceText);
            ilinkMessageReplyService.submitVoice(client, fromUserId, voiceText);
            return;
        }
        if (hasVoiceItem(message)) {
            log.warn("[iLink][VOICE_RECOGNIZE_EMPTY] fromUserId={}", fromUserId);
            ilinkMessageReplyService.submitVoiceRecognitionFailed(client, fromUserId);
            return;
        }
        if (!StringUtils.hasText(text)) {
            return;
        }

        log.info("[iLink][USER_MESSAGE] fromUserId={}, text={}", fromUserId, text);
        ilinkMessageReplyService.submit(client, fromUserId, text);
    }

    private boolean isFromBot(ILinkClient client, String fromUserId) {
        return client.getLoginContext() != null
                && fromUserId.equals(client.getLoginContext().getBotId());
    }

    /** 图片处理：下载字节 → 存图片上下文 → 进回复队列发确认语 */
    private void saveImageContext(ILinkClient client, String userId, MessageItem imageItem) {
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
            imageContextService.save(userId, imageBytes);
            ilinkMessageReplyService.submitImageReceived(client, userId);
            log.info("[iLink][IMAGE_CONTEXT_SAVED] userId={}, imageBytes={}", userId, imageBytes.length);
        } catch (IOException | RuntimeException exception) {
            log.warn("[iLink][IMAGE_CONTEXT_SAVE_FAILED] userId={}, reason={}", userId, exception.getMessage());
        }
    }

    /** 提取文本：遍历 item_list 找第一个有内容的 text_item */
    private String extractText(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item.getText_item() != null && StringUtils.hasText(item.getText_item().getText())) {
                return item.getText_item().getText().trim();
            }
        }
        return null;
    }
    /** 提取语音识别文本：找第一个 voice_item 的 text 字段 */
    private String extractVoiceText(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item.getVoice_item() != null && StringUtils.hasText(item.getVoice_item().getText())) {
                return item.getVoice_item().getText().trim();
            }
        }
        return null;
    }

    //hasVoiceItem(message) 为 true 只代表“有语音”，不代表“有识别文字”。
    private boolean hasVoiceItem(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return false;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item.getVoice_item() != null) {
                return true;
            }
        }
        return false;
    }

    private MessageItem extractImageItem(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item.getImage_item() != null) {
                return item;
            }
        }
        return null;
    }
}