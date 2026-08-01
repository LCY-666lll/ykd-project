package com.fourth.ykd.ai.routing;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/** 使用 DeepSeek 结合近期会话对消息进行意图分类。 */
@Slf4j
@Component
public class DeepSeekIntentRouter {
    private static final Pattern INTENT_PATTERN = Pattern.compile("\\\"intent\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");

    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("(?i)https?://\\S+");

    private final ChatClient routeChatClient;
    private final ChatMemory chatMemory;

    /** 创建独立的意图路由客户端。 */
    public DeepSeekIntentRouter(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory
    ) {
        this.routeChatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
    }


    /**
     * 使用独立 DeepSeek 调用判断当前微信消息应该进入哪个业务分支。
     * Java 只解析模型返回的固定 JSON 意图，不使用关键词或正则决定业务语义。
     */
    public UserIntent route(String conversationId, String userText, boolean hasPendingImage) {
        String result = routeChatClient.prompt()
                .system(buildRouteInstructions(hasPendingImage) + recentConversation(conversationId))
                .user(userText == null ? "" : userText.trim())
                .call()
                .content();
        Matcher matcher = INTENT_PATTERN.matcher(result == null ? "" : result);
        if (!matcher.find()) {
            if (containsExplicitHttpUrl(userText)) {
                log.info("[AI][INTENT_ROUTE] source=URL_FALLBACK, intent=BROWSER_TASK");
                return UserIntent.BROWSER_TASK;
            }
            log.warn("[AI][INTENT_ROUTE] source=MODEL_FALLBACK, intent=TEXT, result={}", result);
            return UserIntent.TEXT;
        }
        try {
            UserIntent intent = UserIntent.valueOf(matcher.group(1));

            if (intent == UserIntent.BROWSER_TASK && !containsExplicitHttpUrl(userText) && !hasRecentPublicUrl(conversationId)) {
                log.warn("[AI][INTENT_ROUTE] source=MODEL_FALLBACK, intent=TEXT, reason=NO_EXPLICIT_URL");
                return UserIntent.TEXT;
            }


            if (intent == UserIntent.TEXT && containsExplicitHttpUrl(userText)) {
                log.info("[AI][INTENT_ROUTE] source=URL_FALLBACK, intent=BROWSER_TASK");
                return UserIntent.BROWSER_TASK;
            }
            log.info("[AI][INTENT_ROUTE] source=MODEL, intent={}", intent);
            return intent;
        } catch (IllegalArgumentException exception) {
            log.warn("[AI][INTENT_ROUTE] source=MODEL_FALLBACK, intent=TEXT, unknownIntent={}",
                    matcher.group(1));
            return UserIntent.TEXT;
        }
    }

    /**
     * 提供最近六条短期会话，帮助路由模型理解“刚才那个”“还是改回去”等省略表达。
     * 近期会话只用于意图分类，不在该方法中执行任何业务操作。
     */
    private String recentConversation(String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        int start = Math.max(0, messages.size() - 6);
        StringBuilder result = new StringBuilder("\n以下是同一用户近期会话，仅用于理解省略指代：\n");
        for (int index = start; index < messages.size(); index++) {
            result.append(messages.get(index).getText()).append('\n');
        }
        return result.toString();
    }

    private boolean hasRecentPublicUrl(String conversationId) {
        return chatMemory.get(conversationId).stream()
                .map(Message::getText)
                .anyMatch(this::containsExplicitHttpUrl);
    }

    private boolean containsExplicitHttpUrl(String userText) {
        return userText != null && HTTP_URL_PATTERN.matcher(userText).find();
    }

    /** 构造仅包含路由规则的系统提示词。 */
    private String buildRouteInstructions(boolean hasPendingImage) {
        String intents = hasPendingImage
                ? "TEXT, MEMORY_MANAGE, IMAGE_GENERATE, IMAGE_EDIT, IMAGE_UNDERSTAND, FILE_GENERATE, VOICE_REPLY, BROWSER_TASK"
                : "TEXT, MEMORY_MANAGE, IMAGE_GENERATE, FILE_GENERATE, VOICE_REPLY, BROWSER_TASK";
        return """
                你是消息意图路由器，只负责选择意图，不负责回答、搜索、整理内容或生成文件。
                必须从以下可选意图中选择一个：%s。
                FILE_GENERATE：用户要求把内容生成、导出、下载或整理成文件时使用；格式包括 PDF、DOCX、Word、XLSX、Excel。
                即使请求包含搜索、查询、整理或总结，只要要求导出文件，仍必须选择 FILE_GENERATE。
                若近期会话中的上一项任务是生成或导出文件，用户说“再生成”“重新生成”“按上面生成”或“给我生成”时，必须选择 FILE_GENERATE。
                IMAGE_UNDERSTAND：仅当用户要求重新分析当前图片、读取文字或二维码、核对局部细节，或者近期会话中没有可用的【图片识别记忆】时使用。
                IMAGE_EDIT：用户本轮明确要求修改、延展或变换当前图片时使用；询问以前为什么这样修改、修改依据或结果来源时不属于图片编辑。
                IMAGE_GENERATE：用户本轮明确要求生成一张独立新图片且不使用当前图片时使用；询问以前生成图片的依据、来源、过程或原因时选择 TEXT。
                VOICE_REPLY：仅当用户明确要求机器人使用语音、声音回答，或把内容读出来时使用。用户发送的是语音消息，不代表要求语音回复。
                图片理解、图片编辑、图片生成和文件生成请求优先选择各自意图，不因同时出现“语音”而改选 VOICE_REPLY。
                MEMORY_MANAGE：仅当用户明确要求长期记住、修改、纠正、忘记或删除某项个人信息、偏好、任务或项目事实时使用。询问“你记得什么”“我的默认城市是什么”只是查询已有记忆，应选择 TEXT。
                BROWSER_TASK：仅当用户提供明确的公开 http 或 https 网址，
                并且要求实际打开网页、点击链接、填写非敏感查询条件、筛选、翻页、
                等待动态页面或读取网页结果时使用。
                普通“搜索某信息”“查一下新闻”等请求仍选择 TEXT。
                用户只发送公开网址、但未说明要做什么时，也选择 BROWSER_TASK；
                后续浏览器任务会提示用户补充读取、总结、查找、筛选或点击等明确动作，
                若近期会话含公开网址，用户追问“这个网址”或“上面文章”的公开内容时，可选择 BROWSER_TASK 并重新访问该网址。
                不得把这种情况当作普通 TEXT 聊天。

                本轮和近期会话都没有公开网址时，不得选择 BROWSER_TASK。
                TEXT：普通对话、知识问答、搜索请求或文字任务，且没有要求生成、导出或下载文件。
                “帮我写一篇文章”选择 TEXT；只有明确要求导出、下载或生成文件时才选择 FILE_GENERATE。
                “用表格列出”不等于 XLSX；只有明确要求 Excel、XLSX、电子表格或表格文件时才选择 FILE_GENERATE。
                存在当前图片且近期会话已有【图片识别记忆】时，“刚才识别结果是什么”“图片大概有什么”选择 TEXT；“重新识别”“读取小字”“识别二维码”“核对局部细节”选择 IMAGE_UNDERSTAND。
                请求包含多个连续业务任务时，选择用户最终要求交付的主要结果类型。
                只能返回 JSON 对象，格式必须严格为 {"intent":"TEXT"}，不要输出解释、Markdown、文件内容或其他文字。
                """.formatted(intents);
    }
}
