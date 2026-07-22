package com.fourth.ykd.ai.routing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/* AI 消息路由器：DeepSeek 仍然负责路由判断，通过 Spring AI 调用。 */
@Slf4j
@Component
public class DeepSeekIntentRouter {

    private static final Pattern INTENT_PATTERN =
            Pattern.compile("\"intent\"\\s*:\\s*\"([A-Z_]+)\"");

    private final ChatClient routeChatClient;

    public DeepSeekIntentRouter(ChatClient.Builder chatClientBuilder) {
        this.routeChatClient = chatClientBuilder.build();
    }

    public UserIntent route(String userText) {
        return route(userText, false);
    }

    public UserIntent route(String userText, boolean hasPendingImage) {
        String routeResult = routeChatClient.prompt()
                .user(buildPrompt(userText, hasPendingImage))
                .call()
                .content();
        Matcher matcher = INTENT_PATTERN.matcher(routeResult == null ? "" : routeResult);
        if (!matcher.find()) {
            return UserIntent.TEXT;
        }
        try {
            return UserIntent.valueOf(matcher.group(1));
        } catch (IllegalArgumentException e) {
            log.warn("DeepSeek 返回了未知意图: {}", matcher.group(1));
            return UserIntent.TEXT;
        }
    }

    private String buildPrompt(String userText, boolean hasPendingImage) {
        String availableIntents = hasPendingImage
                ? "TEXT, IMAGE_GENERATE, IMAGE_EDIT, IMAGE_UNDERSTAND"
                : "TEXT, IMAGE_GENERATE";
        return """
                你是消息路由器。请根据用户当前这句话的真实目标，选择一个最合适的意图。
                可选意图：%s
                IMAGE_UNDERSTAND 表示用户希望理解、判断或获取当前图片的信息。
                IMAGE_EDIT 表示用户希望把当前图片作为输入进行修改、延展、变换或作为参考素材。
                IMAGE_GENERATE 表示用户希望生成一张独立的新图片，且不需要使用当前图片。
                TEXT 表示普通对话或与图片无关的文字任务。
                只返回 JSON 对象，格式必须类似 {"intent":"TEXT"}，不要补充解释。
                用户内容：%s
                """.formatted(availableIntents, userText);
    }
}