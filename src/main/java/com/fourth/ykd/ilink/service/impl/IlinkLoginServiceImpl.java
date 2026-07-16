package com.fourth.ykd.ilink.service.impl;

import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.exception.BusinessException;
import com.fourth.ykd.ilink.config.IlinkProperties;
import com.fourth.ykd.ilink.dto.IlinkLoginQrResponse;
import com.fourth.ykd.ilink.dto.IlinkLoginStatusResponse;
import com.fourth.ykd.ilink.service.IlinkLoginService;
import com.fourth.ykd.ilink.session.IlinkLoginSessionStore;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class IlinkLoginServiceImpl implements IlinkLoginService {

    private final IlinkProperties properties;
    private final IlinkLoginSessionStore sessionStore;
    private final AiChatService aiChatService;
    private final ExecutorService replyExecutor = Executors.newFixedThreadPool(2);
    private volatile ILinkClient client;

    public IlinkLoginServiceImpl(IlinkProperties properties, IlinkLoginSessionStore sessionStore,
                                 AiChatService aiChatService) {
        this.properties = properties;
        this.sessionStore = sessionStore;
        this.aiChatService = aiChatService;
    }

    @PostConstruct
    void restoreSessionIfEnabled() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LoginContext context = sessionStore.load();
            if (context != null) {
                client = buildClient(context);
                log.info("[iLink] session restored, botId={}", context.getBotId());
            }
        } catch (IOException exception) {
            log.warn("[iLink] session restore failed: {}", exception.getMessage());
        }
    }

    @Override
    public synchronized IlinkLoginQrResponse startLogin() {
        closeClient();
        client = buildClient(null);
        try {
            return new IlinkLoginQrResponse(client.executeLogin(), getLoginStatus().status());
        } catch (RuntimeException exception) {
            closeClient();
            throw new BusinessException(50010, "iLink QR-code login could not be started");
        }
    }

    @Override
    public synchronized IlinkLoginStatusResponse getLoginStatus() {
        if (client == null) {
            return new IlinkLoginStatusResponse("NOT_STARTED", false);
        }
        return new IlinkLoginStatusResponse(client.getLoginStatus().getStatus().name(), client.isLoggedIn());
    }

    @Override
    public synchronized void cancelLogin() {
        if (client != null) {
            client.cancelLogin();
            closeClient();
        }
    }

    @Scheduled(fixedDelay = 500)
    void pollMessages() {
        ILinkClient current = client;
        if (current == null || !current.isLoggedIn()) {
            return;
        }
        try {
            for (WeixinMessage message : current.getUpdates()) {
                submitReply(current, message);
            }
        } catch (IOException exception) {
            log.warn("[iLink] receive failed: {}", exception.getMessage());
        }
    }

    private void submitReply(ILinkClient current, WeixinMessage message) {
        String text = getText(message);
        if (!StringUtils.hasText(text) || !StringUtils.hasText(message.getFrom_user_id())) {
            return;
        }
        log.info("[weixin] received from {}: {}", message.getFrom_user_id(), text);
        try {
            current.startTyping(message.getFrom_user_id());
        } catch (IOException exception) {
            log.warn("[weixin] typing start failed: {}", exception.getMessage());
        }
        replyExecutor.execute(() -> reply(current, message.getFrom_user_id(), text));
    }

    private void reply(ILinkClient current, String userId, String text) {
        long start = System.currentTimeMillis();
        try {
            String answer = text.contains("\u5976\u8336")
                    ? "\u53ef\u4ee5\u5e2e\u4f60\u63a8\u8350\u5976\u8336\uff0c\u4f46\u76ee\u524d\u4e0d\u652f\u6301\u771f\u5b9e\u4e0b\u5355\u3002\u4f60\u559c\u6b22\u5976\u8336\u3001\u679c\u8336\u8fd8\u662f\u5496\u5561\uff1f"
                    : aiChatService.chat(text).reply();
            current.sendText(userId, answer);
            log.info("[weixin] replied in {}ms: {}", System.currentTimeMillis() - start, answer);
        } catch (Exception exception) {
            log.warn("[weixin] reply failed in {}ms: {}", System.currentTimeMillis() - start, exception.getMessage());
            try {
                current.sendText(userId, "\u62b1\u6b49\uff0c\u521a\u624d\u5904\u7406\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");
            } catch (IOException ignored) {
                log.warn("[weixin] fallback reply failed");
            }
        } finally {
            try {
                current.stopTyping(userId);
            } catch (IOException ignored) {
                log.debug("[weixin] typing stop failed");
            }
        }
    }

    private String getText(WeixinMessage message) {
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

    void closeClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @PreDestroy
    void shutdown() {
        replyExecutor.shutdownNow();
        closeClient();
    }

    private ILinkClient buildClient(LoginContext context) {
        ILinkClientBuilder builder = ILinkClient.builder().onLogin(new OnLoginListener() {
            @Override
            public void onLoginSuccess(LoginContext loginContext) {
                try {
                    sessionStore.save(loginContext);
                    log.info("[iLink] login succeeded, botId={}", loginContext.getBotId());
                } catch (IOException exception) {
                    log.error("[iLink] session save failed", exception);
                }
            }

            @Override
            public void onLoginFailure(Throwable exception) {
                log.warn("[iLink] login failed", exception);
            }
        });
        return context == null ? builder.build() : builder.loginContext(context).build();
    }
}