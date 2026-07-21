package com.fourth.ykd.ai.routing;

import com.fourth.ykd.ai.infrastructure.deepseek.DeepSeekClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
/*AI消息分流：用 DeepSeek 判断用户这句话要走哪条链路。只做分类，不负责最终回答，把判断结果转化为枚举状态*/
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekIntentRouter {

    //从 DeepSeek 返回的 {"intent":"IMAGE_EDIT"} 里，把 IMAGE_EDIT 提取出来
    private static final Pattern INTENT_PATTERN =
            Pattern.compile("\"intent\"\\s*:\\s*\"([A-Z_]+)\"");

    private final DeepSeekClient deepSeekClient;

    public UserIntent route(String userText) {
        return route(userText, false);
    }

    public UserIntent route(String userText, boolean hasPendingImage) {
        Matcher matcher = INTENT_PATTERN.matcher(deepSeekClient.chat(buildPrompt(userText, hasPendingImage)));
        if (!matcher.find()) {
            return UserIntent.TEXT;
        }
        try {
            /*matcher.group(0) 拿到的是整个匹配内容："intent":"IMAGE_EDIT"
            而：matcher.group(1) 拿到的是第一个圆括号捕获到的内容：IMAGE_EDIT*/
            return UserIntent.valueOf(matcher.group(1));
          //这里可能出现异常，但这个异常不用继续处理，直接走默认逻辑。
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
                你是消息路由器。根据用户当前这句话的真实目标，选择一个最合适的意图。
                可选意图：""" + availableIntents + """
                IMAGE_UNDERSTAND 表示用户希望理解、判断或获取当前图片的信息。
                IMAGE_EDIT 表示用户希望把当前图片作为输入进行修改、延展、变换或作为参考素材。
                IMAGE_GENERATE 表示用户希望生成一张独立的新图片，且不需要使用当前图片。
                TEXT 表示普通对话或与图片无关的文字任务。
                只返回 JSON 对象，格式为 {"intent":"其中一个可选意图"}，不要补充解释。
                用户内容：""" + userText;
    }
}