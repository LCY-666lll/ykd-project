package com.fourth.ykd.ai.routing;
import java.util.regex.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** 使用 DeepSeek 对消息进行意图分类。 */
@Slf4j
@Component
public class DeepSeekIntentRouter {
    private static final Pattern INTENT_PATTERN = Pattern.compile("\\\"intent\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private final ChatClient routeChatClient;
    /** 创建独立的意图路由客户端。 */
    public DeepSeekIntentRouter(ChatClient.Builder chatClientBuilder) { this.routeChatClient = chatClientBuilder.build(); }
    /** 按默认条件路由用户消息。 */
    public UserIntent route(String userText) { return route(userText, false); }
    /** 按图片上下文可用性路由用户消息。 */
    public UserIntent route(String userText, boolean hasPendingImage) {
        String result = routeChatClient.prompt().system(buildRouteInstructions(hasPendingImage))
                .user(userText == null ? "" : userText.trim()).call().content();
        Matcher matcher = INTENT_PATTERN.matcher(result == null ? "" : result);
        if (!matcher.find()) { log.warn("意图路由结果无法识别，按普通文本处理，结果={}", result); return UserIntent.TEXT; }
        try { return UserIntent.valueOf(matcher.group(1)); }
        catch (IllegalArgumentException exception) { log.warn("意图路由返回未知意图，按普通文本处理，意图={}", matcher.group(1)); return UserIntent.TEXT; }
    }
    /** 构造仅包含路由规则的系统提示词。 */
    private String buildRouteInstructions(boolean hasPendingImage) {
        String intents = hasPendingImage ? "TEXT, IMAGE_GENERATE, IMAGE_EDIT, IMAGE_UNDERSTAND, FILE_GENERATE" : "TEXT, IMAGE_GENERATE, FILE_GENERATE";
        return """
                你是消息意图路由器，只负责选择意图，不负责回答、搜索、整理内容或生成文件。
                必须从以下可选意图中选择一个：%s。
                FILE_GENERATE：用户要求把内容生成、导出、下载或整理成文件时使用；格式包括 PDF、DOCX、Word、XLSX、Excel。
                即使请求包含搜索、查询、整理或总结，只要要求导出文件，仍必须选择 FILE_GENERATE。
                IMAGE_UNDERSTAND：用户希望理解、判断或获取当前图片的信息。
                IMAGE_EDIT：用户希望修改、延展或变换当前图片。
                IMAGE_GENERATE：用户希望生成独立新图片且不使用当前图片。
                TEXT：普通对话、知识问答、搜索请求或文字任务，且没有要求生成、导出或下载文件。
                只能返回 JSON 对象，格式必须严格为 {"intent":"TEXT"}，不要输出解释、Markdown、文件内容或其他文字。
                """.formatted(intents);
    }
}
