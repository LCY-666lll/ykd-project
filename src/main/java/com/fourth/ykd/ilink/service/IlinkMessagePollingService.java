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

    @Scheduled(fixedDelayString = "${ilink.poll-delay-ms:500}")
    public void pollMessages() {
        clientManager.findClient()
                .filter(ILinkClient::isLoggedIn)
                .ifPresent(this::pullMessages);
    }

    private void pullMessages(ILinkClient client) {
        try {
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