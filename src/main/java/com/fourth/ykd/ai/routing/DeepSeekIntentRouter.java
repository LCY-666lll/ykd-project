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
        /*SpringAI链式写法：
        1.prompt()：routeChatClient.prompt()：开始构造一次大模型请求。
        2.user()：user(buildPrompt(userText, hasPendingImage)) ：设置用户消息。
        这里不是直接把原始的 userText 发给模型，而是先调用buildPrompt(userText, hasPendingImage)构造完整提示词。
        3.call():真正向 DeepSeek 发起请求。这是一个同步调用。当前线程会等待模型返回结果之后，才继续向下执行.
        4.content():取出模型返回的文字内容。*/
                String routeResult = routeChatClient.prompt()
                .user(buildPrompt(userText, hasPendingImage))
                .call()
                .content();
        Matcher matcher = INTENT_PATTERN.matcher(routeResult == null ? "" : routeResult);
        if (!matcher.find()) {
            return UserIntent.TEXT;
        }
        try {
            //字符串转换为枚举
            return UserIntent.valueOf(matcher.group(1));
        } catch (IllegalArgumentException e) {
            log.warn("DeepSeek 返回了未知意图: {}", matcher.group(1));
            return UserIntent.TEXT;
        }
    }

    private String buildPrompt(String userText, boolean hasPendingImage) {
        String availableIntents = hasPendingImage
                ? "TEXT, IMAGE_GENERATE, IMAGE_EDIT, IMAGE_UNDERSTAND, FILE_GENERATE"
                : "TEXT, IMAGE_GENERATE, FILE_GENERATE";
        return """
                你是消息意图路由器，只负责选择意图，不负责回答、搜索、整理内容或生成文件。
                必须从以下可选意图中选择一个：%s。

                FILE_GENERATE：用户要求把内容生成、导出、下载或整理成文件时使用；文件格式包括 PDF、DOCX、Word、XLSX、Excel。
                只要请求同时包含“搜索、查询、整理、总结”等内容和“导出为文件”的要求，仍必须选择 FILE_GENERATE。
                示例：
                - “帮我生成学习计划并导出成 PDF” -> {"intent":"FILE_GENERATE"}
                - “搜索软件工程资料后整理成 Word 和 Excel” -> {"intent":"FILE_GENERATE"}
                - “把刚才内容导出为 PDF” -> {"intent":"FILE_GENERATE"}

                IMAGE_UNDERSTAND：用户希望理解、判断或获取当前图片的信息。
                IMAGE_EDIT：用户希望把当前图片作为输入进行修改、延展、变换或作为参考素材。
                IMAGE_GENERATE：用户希望生成一张独立的新图片，且不需要使用当前图片。
                TEXT：普通对话、知识问答、搜索请求或文字任务，且没有要求生成、导出或下载文件。

                只能返回 JSON 对象，格式必须严格为 {"intent":"TEXT"}，不要输出解释、Markdown、文件内容或其他文字。
                用户内容开始：
                %s
                用户内容结束。
                """.formatted(availableIntents, userText);
    }
}