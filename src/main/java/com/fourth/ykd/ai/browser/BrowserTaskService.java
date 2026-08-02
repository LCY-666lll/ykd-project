package com.fourth.ykd.ai.browser;

import com.fourth.ykd.ai.mcp.BrowserMcpToolProvider;
import com.fourth.ykd.ai.utils.WeatherTool;
import java.util.Arrays;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 浏览器任务的业务入口：校验网址、隔离记忆并只提供允许的 MCP 工具。 */
@Slf4j
@Service
public class BrowserTaskService {

    private static final int TASK_MEMORY_MAX_MESSAGES = 16;

    private static final String BROWSER_TASK_INSTRUCTIONS = """
            你是微信机器人中的公开网页浏览助手。只完成用户给出的公开网址上的低风险任务。
            允许读取无需登录即可见的标题、正文、列表、公告、日期、作者和公开元数据；
            也允许在用户明确要求后，通过点击、筛选、翻页或查找获得公开内容，
            并对这些公开内容进行摘要、提取、比较和整理。
            若用户只提供网址而未说明任何任务，要求用户补充操作，不调用浏览器工具。

            读取、点击、筛选、翻页和快照公开网页时使用浏览器 MCP 工具。
            用户明确询问当前、实时、今天或未来天气时，必须调用现有天气工具：
            当前或实时天气调用 query_current_weather；未来天气、这几天天气或天气预报调用 query_weather_forecast。
            不得打开天气网站替代天气工具。
            任务完成后，用简洁中文说明结果和必要的页面来源。
            不得猜测页面内容；需要实时信息时必须以本轮工具返回结果为准。

            严格禁止登录、验证码、短信或扫码验证、支付、购买、删除、发布、上传文件、
            任意 JavaScript 执行、读取本地文件，以及绕过网站安全策略。
            不得读取登录、付费墙、验证码、短信验证或扫码验证之后的内容；
            不得读取账号密码、Cookie、Token、个人资料、订单、私信或其他私人数据；
            不得下载文件。
            如果页面要求上述操作、无法访问、出现验证码、找不到元素或超时，说明具体原因并停止。
            """;

    private final BrowserUrlPolicy browserUrlPolicy;
    private final BrowserMcpToolProvider browserMcpToolProvider;
    private final WeatherTool weatherTool;
    private final BrowserTaskChatClientFactory chatClientFactory;

    public BrowserTaskService(
            BrowserUrlPolicy browserUrlPolicy,
            BrowserMcpToolProvider browserMcpToolProvider,
            BrowserTaskChatClientFactory chatClientFactory,
            WeatherTool weatherTool
    ) {
        this.browserUrlPolicy = browserUrlPolicy;
        this.browserMcpToolProvider = browserMcpToolProvider;
        this.chatClientFactory = chatClientFactory;
        this.weatherTool = weatherTool;
    }

    public String execute(String userId, String userText) {
        BrowserUrlPolicy.ValidationResult validationResult = browserUrlPolicy.validateUserUrl(userText);
        if (!validationResult.allowed()) {
            return "浏览器任务未执行：" + validationResult.message();
        }

        ToolCallback[] toolCallbacks = browserMcpToolProvider.getSafeToolCallbacks();
        if (toolCallbacks.length == 0) {
            return "浏览器功能暂未启用或 MCP 未正常启动，请稍后再试。";
        }

        try {
            ChatMemory taskMemory = MessageWindowChatMemory.builder()
                    .maxMessages(TASK_MEMORY_MAX_MESSAGES)
                    .build();
            String answer = chatClientFactory.create(taskMemory)
                    .prompt()
                    .system(BROWSER_TASK_INSTRUCTIONS)
                    .user(userText)
                    .advisors(advisorSpec -> advisorSpec.param(
                            ChatMemory.CONVERSATION_ID, "browser-task-" + userId + "-" + UUID.randomUUID()))
                    .tools(weatherTool)
                    .toolCallbacks(toolCallbacks)
                    .call()
                    .content();
            if (!StringUtils.hasText(answer)) {
                return "浏览器任务未获得有效结果，请稍后重试。";
            }
            return answer.trim();
        } catch (Exception e) {
            return "浏览器任务执行失败，可能是页面不可访问、元素找不到、出现验证码或工具超时，请稍后重试。";
        } finally {
            closeBrowserQuietly(userId, toolCallbacks);
        }
    }

    private void closeBrowserQuietly(String userId, ToolCallback[] toolCallbacks) {
        Arrays.stream(toolCallbacks)
                .filter(callback -> callback.getToolDefinition() != null)
                .filter(callback -> "browser_close".equals(callback.getToolDefinition().name()))
                .findFirst()
                .ifPresent(callback -> {
                    try {
                        callback.call("{}");
                        log.info("[AI][BROWSER_TASK][CLEANUP] userId={}, action=BROWSER_CLOSE", userId);
                    } catch (RuntimeException exception) {
                        log.warn("[AI][BROWSER_TASK][CLEANUP_FAILED] userId={}", userId, exception);
                    }
                });
    }
}
