package com.fourth.ykd.ai.routing;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/** 使用高置信度本地规则和 DeepSeek 对消息进行意图分类。 */
@Slf4j
@Component
public class DeepSeekIntentRouter {
    private static final Pattern INTENT_PATTERN = Pattern.compile("\\\"intent\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private static final Pattern FILE_ACTION_PATTERN = pattern(
            "生成|制作|创建|导出|下载|保存为|保存成|另存为|整理成|转换成|转换为|转成|转为|做成|做个|放进|放入|写入");
    private static final Pattern FILE_TYPE_PATTERN = pattern(
            "(?<![A-Z0-9])(PDF|DOCX?|WORD|XLSX?|EXCEL)(?![A-Z0-9])|文件|文档");
    private static final Pattern TABLE_FILE_PATTERN = pattern(
            "(生成|制作|创建|导出|下载|保存为|另存为).{0,8}(表格文件|电子表格)"
                    + "|(表格文件|电子表格).{0,8}(生成|制作|创建|导出|下载|保存)");
    private static final Pattern IMAGE_GENERATE_PATTERN = pattern(
            "(生成|画|绘制|制作|设计|创建|做个|做一张).{0,10}(图片|图像|一张图|海报|插画|封面|头像|壁纸)"
                    + "|(图片|图像|一张图|海报|插画|封面|头像|壁纸).{0,10}(生成|画|绘制|制作|设计|创建|做)");
    private static final Pattern IMAGE_EDIT_ACTION_PATTERN = pattern(
            "修改|编辑|调整|替换|换成|改成|改变|去掉|删掉|删除|添加|加上|扩图|抠图|修图|去水印");
    private static final Pattern IMAGE_REFERENCE_PATTERN = pattern(
            "图片|照片|图像|图中|图里|画面|背景|人物|水印|海报|这张图|这个图|当前图|原图");
    private static final Pattern IMAGE_UNDERSTAND_PATTERN = pattern(
            "(识别|分析|描述|看看|看一下|提取|读取).{0,8}(图片|照片|图像|图中|图里|画面|内容|文字)"
                    + "|(图片|照片|图像|图中|图里|画面).{0,10}(是什么|有什么|是谁|写了什么|说了什么|识别|分析|描述|看看|提取|读取)"
                    + "|^(帮我)?识别一下$|^这是什么[？?]?$|^这是谁[？?]?$");
    private static final Pattern VOICE_REPLY_PATTERN = pattern(
            "用语音.{0,8}(回复|回答|告诉|说)|语音回复|语音回答|说给我听|读给我听|读出来"
                    + "|发一段语音|用声音.{0,8}(回复|回答|告诉)");
    private static final Pattern CREATE_TASK_PATTERN = pattern(
            "(提醒|通知).{0,10}(我|一下|我一下)"
                    + "|(设置|创建|新建|添加|加个|定个|设个).{0,10}(提醒|闹钟|定时|任务)"
                    + "|^\\s*(提醒我|通知我|叫我)");
    private static final Pattern DELETE_TASK_PATTERN = pattern(
            "(取消|删除|移除|关闭|停用|清空|清掉).{0,8}(提醒|闹钟|定时|任务)"
                    + "|(提醒|闹钟|定时|任务).{0,8}(取消|删除|不要了|关掉|清掉)");
    // BugFix#3: 排除被误判为 CREATE_TASK 的疑问/抱怨/查询句
    // 例如"为什么没提醒我""怎么没提醒""有什么提醒"等不应触发创建提醒
    private static final Pattern TASK_NEGATIVE_PATTERN = pattern(
            "^\\s*(为什么|怎么|为啥|是不是|有没有|没有|怎么没|为什么没|咋没)"
                    + "|(没提醒我|没通知我|提醒呢|通知呢|怎么没提醒|为什么没提醒|咋没提醒)"
                    + "|(有什么提醒|有哪些提醒|查看提醒|提醒列表|当前提醒|我的提醒|所有提醒|我的定时)"
                    + "|(为什么没|怎么没|咋没).{0,5}(提醒|通知|叫我)"
                    + "|(提醒|通知|任务).{0,3}(呢|哪里|在哪|不见了|没了|没来)");


    private final ChatClient routeChatClient;
    private final ChatMemory chatMemory;

    /** 创建独立的意图路由客户端。 */
    public DeepSeekIntentRouter(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        this.routeChatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
    }


    /** 优先匹配明确意图，其余请求继续交给现有 DeepSeek 路由。 */
    public UserIntent route(String conversationId, String userText, boolean hasPendingImage) {
        Optional<UserIntent> explicitIntent = matchExplicitIntent(userText, hasPendingImage);
        if (explicitIntent.isPresent()) {
            log.info("[AI][INTENT_ROUTE] source=LOCAL_RULE, intent={}", explicitIntent.get());
            return explicitIntent.get();
        }

        String result = routeChatClient.prompt()
                .system(buildRouteInstructions(hasPendingImage) + recentConversation(conversationId))
                .user(userText == null ? "" : userText.trim())
                .call()
                .content();
        Matcher matcher = INTENT_PATTERN.matcher(result == null ? "" : result);
        if (!matcher.find()) {
            log.warn("[AI][INTENT_ROUTE] source=MODEL_FALLBACK, intent=TEXT, result={}", result);
            return UserIntent.TEXT;
        }
        try {
            UserIntent intent = UserIntent.valueOf(matcher.group(1));
            log.info("[AI][INTENT_ROUTE] source=MODEL, intent={}", intent);
            return intent;
        } catch (IllegalArgumentException exception) {
            log.warn("[AI][INTENT_ROUTE] source=MODEL_FALLBACK, intent=TEXT, unknownIntent={}",
                    matcher.group(1));
            return UserIntent.TEXT;
        }
    }

    /**
     * 只抢占无需上下文即可确定的明确请求；模糊表达和多业务冲突继续交给模型。
     */
    static Optional<UserIntent> matchExplicitIntent(String userText, boolean hasPendingImage) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }

        Set<UserIntent> businessCandidates = EnumSet.noneOf(UserIntent.class);
        if ((matches(FILE_ACTION_PATTERN, text) && matches(FILE_TYPE_PATTERN, text))
                || matches(TABLE_FILE_PATTERN, text)) {
            businessCandidates.add(UserIntent.FILE_GENERATE);
        }
        if (matches(IMAGE_GENERATE_PATTERN, text)) {
            businessCandidates.add(UserIntent.IMAGE_GENERATE);
        }
        if (hasPendingImage
                && matches(IMAGE_EDIT_ACTION_PATTERN, text)
                && matches(IMAGE_REFERENCE_PATTERN, text)) {
            businessCandidates.add(UserIntent.IMAGE_EDIT);
        }
        if (hasPendingImage && matches(IMAGE_UNDERSTAND_PATTERN, text)) {
            businessCandidates.add(UserIntent.IMAGE_UNDERSTAND);
        }

        if (businessCandidates.size() == 1) {
            return Optional.of(businessCandidates.iterator().next());
        }
        if (businessCandidates.size() > 1) {
            return Optional.empty();
        }
        // BugFix#3: 排除疑问/抱怨/查询句，不误判为 CREATE_TASK 或 DELETE_TASK
        // 这类文本交由模型路由（通常走 TEXT 或 QUERY_REMINDER 对话回复）
        if (matches(TASK_NEGATIVE_PATTERN, text)) {
            log.info("[AI][INTENT_ROUTE] source=LOCAL_RULE, taskNegative=true, skip CREATE_TASK/DELETE_TASK, text={}",
                    text);
            return Optional.empty();
        }
        if (matches(CREATE_TASK_PATTERN, text)) {
            return Optional.of(UserIntent.CREATE_TASK);
        }
        if (matches(DELETE_TASK_PATTERN, text)) {
            return Optional.of(UserIntent.DELETE_TASK);
        }
        return matches(VOICE_REPLY_PATTERN, text)
                ? Optional.of(UserIntent.VOICE_REPLY)
                : Optional.empty();
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean matches(Pattern pattern, String text) {
        return pattern.matcher(text).find();
    }

    private String recentConversation(String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        int start = Math.max(0, messages.size() - 6);
        StringBuilder result = new StringBuilder("\n以下是同一用户近期会话，仅用于理解省略指代：\n");
        for (int index = start; index < messages.size(); index++) {
            result.append(messages.get(index).getText()).append('\n');
        }
        return result.toString();
    }

    /** 构造仅包含路由规则的系统提示词。 */
    private String buildRouteInstructions(boolean hasPendingImage) {
        String intents = hasPendingImage
                ? "TEXT, IMAGE_GENERATE, IMAGE_EDIT, IMAGE_UNDERSTAND, FILE_GENERATE, VOICE_REPLY, CREATE_TASK, DELETE_TASK"
                : "TEXT, IMAGE_GENERATE, FILE_GENERATE, VOICE_REPLY, CREATE_TASK, DELETE_TASK";
        return """
                你是消息意图路由器，只负责选择意图，不负责回答、搜索、整理内容或生成文件。
                必须从以下可选意图中选择一个：%s。
                CREATE_TASK：用户要求设置提醒、创建闹钟、定时通知时使用。常见表述包括"提醒我""设置一个提醒""通知我""定个闹钟"等。
                DELETE_TASK：用户要求取消、删除或关闭已有提醒时使用。常见表述包括"取消提醒""删除闹钟""不要提醒了"等。
                FILE_GENERATE：用户要求把内容生成、导出、下载或整理成文件时使用；格式包括 PDF、DOCX、Word、XLSX、Excel。
                即使请求包含搜索、查询、整理或总结，只要要求导出文件，仍必须选择 FILE_GENERATE。
                若近期会话中的上一项任务是生成或导出文件，用户说"再生成""重新生成""按上面生成"或"给我生成"时，必须选择 FILE_GENERATE。
                IMAGE_UNDERSTAND：用户希望理解、判断或获取当前图片的信息。
                IMAGE_EDIT：用户希望修改、延展或变换当前图片。
                IMAGE_GENERATE：用户希望生成独立新图片且不使用当前图片。
                VOICE_REPLY：仅当用户明确要求机器人使用语音、声音回答，或把内容读出来时使用。用户发送的是语音消息，不代表要求语音回复。
                创建任务、删除任务、图片理解、图片编辑、图片生成和文件生成请求优先选择各自意图，不因同时出现"语音"而改选 VOICE_REPLY。
                TEXT：普通对话、知识问答、搜索请求或文字任务，且没有要求生成、导出或下载文件。
                "帮我写一篇文章"选择 TEXT；只有明确要求导出、下载或生成文件时才选择 FILE_GENERATE。
                "用表格列出"不等于 XLSX；只有明确要求 Excel、XLSX、电子表格或表格文件时才选择 FILE_GENERATE。
                存在当前图片时，"换背景"选择 IMAGE_EDIT，"图片里有什么"选择 IMAGE_UNDERSTAND。
                请求包含多个连续业务任务时，选择用户最终要求交付的主要结果类型。
                只能返回 JSON 对象，格式必须严格为 {"intent":"TEXT"}，不要输出解释、Markdown、文件内容或其他文字。
                """.formatted(intents);
    }
}
