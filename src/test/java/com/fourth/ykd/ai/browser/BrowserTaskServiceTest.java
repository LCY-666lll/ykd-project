package com.fourth.ykd.ai.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fourth.ykd.ai.utils.WeatherTool;
import com.fourth.ykd.ai.mcp.BrowserMcpToolProvider;
import java.net.InetAddress;
import java.util.function.Consumer;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

class BrowserTaskServiceTest {

    @Test
    void shouldRejectTaskWithoutExplicitUrlBeforeUsingMcp() throws Exception {
        BrowserMcpToolProvider toolProvider = mock(BrowserMcpToolProvider.class);
        BrowserTaskChatClientFactory chatClientFactory = mock(BrowserTaskChatClientFactory.class);
        BrowserTaskService service = new BrowserTaskService(
                publicUrlPolicy(), toolProvider, chatClientFactory, mock(WeatherTool.class));

        String answer = service.execute("user-1", "帮我打开学校官网");

        assertThat(answer).contains("未执行");
        verifyNoInteractions(toolProvider, chatClientFactory);
    }

    @Test
    void shouldStopGracefullyWhenMcpIsDisabled() throws Exception {
        BrowserMcpToolProvider toolProvider = mock(BrowserMcpToolProvider.class);
        BrowserTaskChatClientFactory chatClientFactory = mock(BrowserTaskChatClientFactory.class);
        when(toolProvider.getSafeToolCallbacks()).thenReturn(new ToolCallback[0]);
        BrowserTaskService service = new BrowserTaskService(
                publicUrlPolicy(), toolProvider, chatClientFactory, mock(WeatherTool.class));

        String answer = service.execute("user-1", "打开 https://example.com");

        assertThat(answer).contains("暂未启用");
        verify(chatClientFactory, never()).create(any());
    }

    @Test
    void shouldReturnBrowserTaskSummaryWhenMcpResponds() throws Exception {
        BrowserMcpToolProvider toolProvider = mock(BrowserMcpToolProvider.class);
        BrowserTaskChatClientFactory chatClientFactory = mock(BrowserTaskChatClientFactory.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ToolCallback browserCloseCallback = toolCallback("browser_close");
        String userText = "打开 https://example.com 并告诉我标题";
        WeatherTool weatherTool = mock(WeatherTool.class);

        when(toolProvider.getSafeToolCallbacks()).thenReturn(new ToolCallback[]{browserCloseCallback});
        when(chatClientFactory.create(any())).thenReturn(chatClient);
        when(chatClient.prompt()
                .system(anyString())
                .user(userText)
                .advisors((Consumer<ChatClient.AdvisorSpec>) any(Consumer.class))
                .tools(weatherTool)
                .toolCallbacks(any(ToolCallback[].class))
                .call()
                .content())
                .thenReturn("页面标题是 Example Domain");

        BrowserTaskService service = new BrowserTaskService(
                publicUrlPolicy(), toolProvider, chatClientFactory, weatherTool);

        String answer = service.execute("user-1", userText);

        assertThat(answer).isEqualTo("页面标题是 Example Domain");
        verify(chatClientFactory).create(any());
        verify(browserCloseCallback).call("{}");
    }

    @Test
    void shouldCloseBrowserWhenBrowserTaskFails() throws Exception {
        BrowserMcpToolProvider toolProvider = mock(BrowserMcpToolProvider.class);
        BrowserTaskChatClientFactory chatClientFactory = mock(BrowserTaskChatClientFactory.class);
        ToolCallback browserCloseCallback = toolCallback("browser_close");
        String userText = "打开 https://example.com 并告诉我标题";

        when(toolProvider.getSafeToolCallbacks()).thenReturn(new ToolCallback[]{browserCloseCallback});
        when(chatClientFactory.create(any())).thenThrow(new IllegalStateException("browser task failed"));

        BrowserTaskService service = new BrowserTaskService(
                publicUrlPolicy(), toolProvider, chatClientFactory, mock(WeatherTool.class));

        String answer = service.execute("user-1", userText);

        assertThat(answer).contains("执行失败");
        verify(browserCloseCallback).call("{}");
    }

    private static ToolCallback toolCallback(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static BrowserUrlPolicy publicUrlPolicy() throws Exception {
        return new BrowserUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34")
        });
    }
}
